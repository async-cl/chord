(ns chord.http-kit
  (:require [clojure.core.async :as a :refer [chan <! >! close! go-loop]]
            [org.httpkit.server :as http]
            [chord.channels :refer [wrap-websocket]]))

(defmacro with-channel
  "Extracts the websocket from the request and binds it to 'ch-name' in the body
   Arguments:
    req         - (required) HTTP-kit request map
    ch-name     - (required) variable to bind the channel to in the body
    opts        - (optional) map to configure reading/writing channels
      :read-ch  - (optional) (possibly buffered) channel to use for reading the websocket
      :write-ch - (optional) (possibly buffered) channel to use for writing to the websocket
      :format   - (optional) data format to use on the channel, (at the moment)
                             either :edn (default), :json, :json-kw, :transit-json, or :str.
         (and any other options your formatter needs)

   Usage:
    (require '[clojure.core.async :as a])

    (with-channel req the-ws
      (a/go-loop []
        (when-let [msg (a/<! the-ws)]
          (println msg)
          (recur))))

    (with-channel req the-ws
      {:read-ch (a/chan (a/sliding-buffer 10))
       :write-ch (a/chan (a/dropping-buffer 5))}

      (go-loop []
        (when-let [msg (<! the-ws)]
          (println msg)
          (recur))))"

  [req ch-name & [opts & body]]

  (let [opts? (and (or (map? opts)
                       (:opts (meta opts)))
                   (seq body))
        body (cond->> body
               (not opts?) (cons opts))
        opts (when opts? opts)]

    `(http/with-channel ~req httpkit-ch#
       (let [~ch-name (wrap-websocket httpkit-ch# ~opts
                                      #(when (http/open? httpkit-ch#)
                                         (http/close httpkit-ch#)))]
         ~@body))))

(defn wrap-websocket-handler
  "Middleware that puts a :ws-channel key on the request map for
   websocket requests.

   Arguments:
    handler - (required) Ring-compatible handler
    opts    - (optional) Options for the WebSocket channel - same options as for `with-channel`"
  [handler & [opts]]

  (fn [req]
    (if (:websocket? req)
      (with-channel req ws-conn
        ^:opts (or opts {})
        (handler (assoc req :ws-channel ws-conn)))
      (handler req))))

(comment
  (defn handler [{:keys [ws-channel] :as req}]
    (go-loop []
      (if-let [{:keys [message]} (<! ws-channel)]
        (do
          (prn {:message message})
          (>! ws-channel (str "You said: " message))
          (recur))
        (prn "closed."))))

  (server)

  (def server (http/run-server (-> #'handler wrap-websocket-handler) {:port 3000})))
