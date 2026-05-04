(ns loom-exporter.tui
  (:require [clojure.string :as str]))

(defn- truncate [s n]
  (let [s (str (or s ""))]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn- parse-int [s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException _
      nil)))

(defn- parse-token [token max-count]
  (if-let [[_ a b] (re-matches #"(\d+)-(\d+)" token)]
    (let [start (parse-int a)
          end (parse-int b)]
      (cond
        (> start end) {:error (str "Invalid range: " token)}
        (or (< start 1) (> end max-count)) {:error (str "Out of range: " token)}
        :else {:indices (range (dec start) end)}))
    (if-let [n (parse-int token)]
      (if (<= 1 n max-count)
        {:indices [(dec n)]}
        {:error (str "Out of range: " token)})
      {:error (str "Invalid selection: " token)})))

(defn parse-selection [input max-count]
  (let [input (str/trim (str input))
        lower (str/lower-case input)]
    (cond
      (#{"" "q" "quit" "cancel"} lower) {:action :cancel}
      (= "all" lower) {:action :select :indices (set (range max-count))}
      (= "none" lower) {:action :select :indices #{}}
      :else
      (let [tokens (->> (str/split lower #",")
                        (map str/trim)
                        (remove str/blank?))
            parsed (map #(parse-token % max-count) tokens)
            error (some :error parsed)]
        (if error
          {:action :error :message error}
          {:action :select
           :indices (set (mapcat :indices parsed))})))))

(defn print-video-picker [videos]
  (println)
  (println "Available videos")
  (println "----------------")
  (doseq [[idx video] (map-indexed vector videos)]
    (println (format "%3d. %-10s %-9s %s"
                     (inc idx)
                     (truncate (:visibility video) 10)
                     (truncate (name (or (:source video) "")) 9)
                     (truncate (:title video) 80)))))

(defn prompt-selection-line [videos]
  (print-video-picker videos)
  (println)
  (println "Select videos to download: 1,3-5,all")
  (println "Enter q or blank to cancel.")
  (loop []
    (print "> ")
    (flush)
    (let [result (parse-selection (read-line) (count videos))]
      (case (:action result)
        :cancel nil
        :select (:indices result)
        :error (do
                 (println (:message result))
                 (recur))))))

(defn- shell! [cmd]
  (let [process (-> (ProcessBuilder. ["sh" "-c" cmd])
                    (.redirectErrorStream true)
                    .start)
        out (slurp (.getInputStream process))
        exit (.waitFor process)]
    {:exit exit :out out}))

(defn- tty? []
  (and (System/console)
       (not= "dumb" (System/getenv "TERM"))))

(defn- stty-state []
  (let [{:keys [exit out]} (shell! "stty -g < /dev/tty")]
    (when (zero? exit)
      (str/trim out))))

(defn- set-raw! []
  (shell! "stty -icanon -echo min 1 time 0 < /dev/tty"))

(defn- restore-stty! [state]
  (when-not (str/blank? state)
    (shell! (str "stty " state " < /dev/tty"))))

(defn- terminal-size []
  (let [{:keys [exit out]} (shell! "stty size < /dev/tty")
        [_ rows cols] (when (zero? exit)
                        (re-find #"(\d+)\s+(\d+)" out))]
    {:rows (or (some-> rows parse-long) 24)
     :cols (or (some-> cols parse-long) 100)}))

(defn- clear-screen! []
  (print "\u001b[H\u001b[2J")
  (flush))

(defn- alternate-screen! []
  (print "\u001b[?1049h\u001b[?25l")
  (flush))

(defn- normal-screen! []
  (print "\u001b[0m\u001b[?25h\u001b[?1049l")
  (flush))

(defn- printable-byte? [b]
  (<= 32 b 126))

(defn- read-key! []
  (let [in System/in
        b (.read in)]
    (cond
      (neg? b) :eof
      (= b 27) (do
                 (Thread/sleep 10)
                 (if (pos? (.available in))
                   (let [b1 (.read in)]
                     (if (= b1 (int \[))
                       (let [b2 (.read in)]
                         (case (char b2)
                           \A :up
                           \B :down
                           \C :right
                           \D :left
                           \H :home
                           \F :end
                           \3 (do
                                (when (pos? (.available in))
                                  (.read in))
                                :delete)
                           :escape))
                       :escape))
                   :escape))
      (or (= b 127) (= b 8)) :backspace
      (= b 13) :enter
      (= b 10) :enter
      (= b 32) :space
      (printable-byte? b) (char b)
      :else :unknown)))

(defn- video-search-text [video]
  (str/lower-case
   (str/join " "
             [(or (:title video) "")
              (or (:id video) "")
              (or (:url video) "")
              (or (:visibility video) "")
              (get-in video [:owner :display_name])
              (get-in video [:owner :name])])))

(defn- filter-videos [videos query]
  (let [q (str/lower-case (str/trim (str query)))]
    (->> videos
         (map-indexed (fn [idx video] {:idx idx :video video}))
         (filter (fn [{:keys [video]}]
                   (or (str/blank? q)
                       (str/includes? (video-search-text video) q))))
         vec)))

(defn- clamp [n low high]
  (-> n (max low) (min high)))

(defn- normalize-state [state item-count page-size]
  (let [cursor (if (pos? item-count)
                 (clamp (:cursor state) 0 (dec item-count))
                 0)
        offset (cond
                 (zero? item-count) 0
                 (< cursor (:offset state)) cursor
                 (>= cursor (+ (:offset state) page-size)) (inc (- cursor page-size))
                 :else (:offset state))]
    (assoc state
           :cursor cursor
           :offset (clamp offset 0 (max 0 (dec item-count))))))

(defn- selected-current [state items]
  (some-> items (nth (:cursor state) nil) :idx))

(defn- render-row [cols highlighted? selected? idx video]
  (let [prefix (format "%s %4d  " (if selected? "[x]" "[ ]") (inc idx))
        meta (format "%-10s %-9s  "
                     (truncate (:visibility video) 10)
                     (truncate (name (or (:source video) "")) 9))
        title-width (max 12 (- cols (count prefix) (count meta) 1))
        row (str prefix meta (truncate (:title video) title-width))]
    (println (if highlighted?
               (str "\u001b[7m" row "\u001b[0m")
               row))))

(defn- render-picker! [videos items state]
  (let [{:keys [rows cols]} (terminal-size)
        page-size (max 5 (- rows 8))
        visible (subvec items
                        (:offset state)
                        (min (count items) (+ (:offset state) page-size)))]
    (clear-screen!)
    (println "Loom exporter - select videos")
    (println "Up/Down or j/k move  Space toggles  / searches  a toggles all shown  n none  Enter continues  q cancels")
    (println (str "Filter: "
                  (if (:searching? state) "\u001b[7m" "")
                  (if (str/blank? (:query state)) "<none>" (:query state))
                  "\u001b[0m"
                  "    "
                  (count (:selected state)) " selected    "
                  (count items) " of " (count videos) " shown"))
    (println (apply str (repeat (min cols 120) "-")))
    (if (seq visible)
      (doseq [[screen-idx {:keys [idx video]}] (map-indexed vector visible)]
        (render-row cols
                    (= (+ (:offset state) screen-idx) (:cursor state))
                    (contains? (:selected state) idx)
                    idx
                    video))
      (println "No matching videos."))
    (flush)
    page-size))

(defn- toggle-selected [selected idx]
  (if (contains? selected idx)
    (disj selected idx)
    (conj selected idx)))

(defn- toggle-all-shown [selected items]
  (let [shown (set (map :idx items))]
    (if (and (seq shown) (every? selected shown))
      (apply disj selected shown)
      (into selected shown))))

(defn- handle-search-key [state key]
  (cond
    (= key :enter) (assoc state :searching? false)
    (= key :escape) (assoc state :searching? false)
    (= key :backspace) (update state :query #(subs % 0 (max 0 (dec (count %)))))
    (char? key) (update state :query str key)
    :else state))

(defn- handle-picker-key [state items key]
  (case key
    :up (update state :cursor dec)
    :down (update state :cursor inc)
    :home (assoc state :cursor 0)
    :end (assoc state :cursor (max 0 (dec (count items))))
    :space (if-let [idx (selected-current state items)]
             (update state :selected toggle-selected idx)
             state)
    :enter (assoc state :done? true)
    :eof (assoc state :cancel? true)
    :escape (if (str/blank? (:query state))
              (assoc state :cancel? true)
              (assoc state :query "" :cursor 0 :offset 0))
    state))

(defn- handle-char-key [state items key]
  (case key
    \j (update state :cursor inc)
    \k (update state :cursor dec)
    \/ (assoc state :searching? true)
    \a (update state :selected toggle-all-shown items)
    \n (assoc state :selected #{})
    \q (assoc state :cancel? true)
    state))

(defn- prompt-selection-screen [videos]
  (let [saved (stty-state)]
    (when (str/blank? saved)
      (throw (ex-info "Could not read terminal state." {:type :terminal-unavailable})))
    (try
      (alternate-screen!)
      (set-raw!)
      (loop [state {:cursor 0
                    :offset 0
                    :query ""
                    :searching? false
                    :selected #{}}]
        (let [{:keys [rows]} (terminal-size)
              page-size (max 5 (- rows 8))
              items (filter-videos videos (:query state))
              state (normalize-state state (count items) page-size)
              _ (render-picker! videos items state)
              key (read-key!)
              state (cond
                      (:searching? state) (handle-search-key state key)
                      (char? key) (handle-char-key state items key)
                      :else (handle-picker-key state items key))
              items (filter-videos videos (:query state))
              state (normalize-state state (count items) page-size)]
          (cond
            (:cancel? state) nil
            (:done? state) (:selected state)
            :else (recur state))))
      (finally
        (restore-stty! saved)
        (normal-screen!)))))

(defn prompt-selection [videos]
  (if (tty?)
    (try
      (prompt-selection-screen videos)
      (catch Exception _
        (prompt-selection-line videos)))
    (prompt-selection-line videos)))
