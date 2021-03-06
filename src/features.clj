(ns features)
(use '[clojure.test :as t])
(use '[chatparse :as cp])
(use '[techterm :as tt])
(use '[unigramstats :as ustats])
(use '[perline :as perline])
(use '[localpath :as lp])

;add features for all messages

(defn basefeat
  [predfn keyset {:keys [curr prev feats] :as m}]
  (apply merge 
         (map #(assoc feats %2 (predfn %1 %2)) [curr prev] keyset)))

(with-test
  (defn greet
    [m]
    (let [predfn (fn [x y] (if (nil? (x :hasgreet)) 0 1))]
          (basefeat predfn ["curr_greet" "prev_greet"] m)))
  (let [c (cp/parseline "T-1 7118 Felice : hello")
        p (cp/parseline "T-1 7115 Felice : hi")
        p2 (cp/parseline "T-1 7115 Felice : howdy man")
        m {:curr c :prev p}]
    (is (= 1 ((greet m) "curr_greet")))
    (is (= 0 ((greet {:curr c :prev p2}) "prev_greet")))))

(defn q&afeat 
  [wordset keyset m]
  (basefeat (fn[x y] (if (some wordset (x :words)) 1 0)) keyset m))

(with-test
  (defn answer-word
     [m]
     (let [answers #{"yes", "yeah", "ok", "no", "nope"}
           kset ["curr_answer" "prev_answer"]]
       (q&afeat answers kset m)))
   (let [c (cp/decoratemsg (cp/parseline "T-1 7118 Felice : yes how is it") #{}) 
         p (cp/decoratemsg (cp/parseline "T-1 7115 Felice : nothing much") #{})
         m {:curr c :prev p :feats {}}]
     (is (= 1 ((answer-word m) "curr_answer")))
    (is (= 0 ((answer-word m) "prev_answer")))))

(with-test
  (defn thanks
     [m]
     (let [answers #{"thank", "thanks", "thx"}
           kset ["curr_thx" "prev_thx"]]
       (q&afeat answers kset m)))
   (let [c (cp/decoratemsg (cp/parseline "T-1 7118 Felice : yes thanks") #{}) 
         p (cp/decoratemsg (cp/parseline "T-1 7115 Felice : nothing much") #{})
         m {:curr c :prev p :feats {}}]
     (is (= 1 ((thanks m) "curr_thx")))
     (is (= 0 ((thanks m) "prev_thx")))))

(with-test
  (defn question
    [m]
    (let [predfn (fn [x y] (if (x :hasq) 1 0))]
          (basefeat predfn ["curr_q" "prev_q"] m)))
  (let [c (cp/decoratemsg (cp/parseline "T-1 7118 Felice : happy?") #{}) 
         p (cp/decoratemsg (cp/parseline "T-1 7115 Felice : not really") #{})
         m {:curr c :prev p :feats {}}]
     (is (= 1 ((question m) "curr_q")))
    (is (= 0 ((question m) "prev_q")))))

(with-test
  (defn speaker 
    [{:keys [curr prev feats] :as m}]
    (assoc feats "same_spk" 
           (if (.equalsIgnoreCase
                 (:speaker curr) (:speaker prev))
                 1 0)))
  (let [c (cp/decoratemsg (cp/parseline "T-1 7118 Felice : happy?") #{}) 
        p (cp/decoratemsg (cp/parseline "T-1 7115 Felice : not really") #{})
        m {:curr c :prev p :feats {}}]
     (is (= 1 ((speaker m) "same_spk")))))

(defn mention
  [{:keys [curr prev feats] :as m}]
  (assoc feats 
         "prev_mentions_curr" 
         (if ((:mentioned curr) (:speaker prev))1 0)
         "curr_mentions_prev"
         (if ((:mentioned prev) (:speaker curr))1 0)
         "same_mention"
         (if (empty? (clojure.set/intersection 
                       (:mentioned curr)
                       (:mentioned prev))) 0 1)
         "prev_mentions"
         (if (empty? (:mentioned prev)) 0 1)
         "curr_mentions"
         (if (empty? (:mentioned curr)) 0 1)))

(defn flength 
  [m]
  (basefeat (fn[x y] (if (> (count (x :words)) 10) 1 0))
            ["curr_long","prev_long"] m))
         
(defn deltaT
  [{:keys [curr prev feats] :as m}]
   (let [dt (- (:timestamp curr) (:timestamp prev))
        bin (int (float (/ (Math/log (+ 1 dt)) (Math/log 1.5))))]
     (assoc feats (str "dt_" bin) 1)))

(defn repeatword
  "needs unigram probability"
  [unigramProb {:keys [curr prev feats] :as m}]     
  (let [x (flatten (filter #(not (empty? %)) 
                           (for [prevword (prev :words)]
                            (for [currword (curr :words)
                                  :let [wp (get unigramProb prevword 0)]
                                  :when (and (> wp 0) (.equals prevword currword))]
                              (let [bin (- (int (float (/ (Math/log wp) (Math/log 10)))))
                                    ftype (str "repeat_" bin)]
                                ;(println (str " word repeats ", prevword, " ", wp, "ftype", ftype))
                                {ftype 1})))))]
    (merge feats (apply merge-with (fn[a b] (+ a b)) x))))

(defn hastech
  "needs linuxwords "
  [linuxwords {:keys [curr prev feats] :as m}]
  (let [[techprev techcurr] (map #(tt/techTerm % linuxwords)
                                 [prev curr])]
    (merge feats (cond (and techprev techcurr) { "both_tech" 1} 
                   (or techprev techcurr) {"one_tech" 1}
                   :else {"neither_tech" 1}))))

(defn get-feats
  "get a list of feature functions"
  [linuxwords ugram]
  [greet  answer-word deltaT (partial hastech linuxwords) 
   (partial repeatword ugram)
   flength  mention speaker  question thanks])

(defn pairfeats
  [featfuncs prev curr]
  (do (println (str " in pairfeats " curr))
  (merge {:same (if (.equals (:thread prev) (:thread curr)) 1 0)} 
    (reduce (fn[ x f] (f {:prev prev :curr curr :feats x})) {} featfuncs))))

(defn pairfeats-sbformat
  [featfuncs prev curr]
  (do ;(println (str " in pairfeats " curr))
  (merge {} 
    (reduce (fn[ x f] (f {:prev prev :curr curr :feats x})) {} featfuncs))))

(defn generate-features
  [infile]
  (let [chats  (cp/generate-msgs infile)
        blocksize 129
        ugrambase (ustats/load-unigrams (str lp/ldir "unigrams.txt")) 
        ugram (:unigrams ugrambase)
        linuxwords (set (map #(.trim %) 
                             (.split 
                               (slurp 
                                 (str lp/ldir "mytechwords.dump")) "\n")))
        featfuncs [greet  answer-word deltaT (partial hastech linuxwords) 
                   (partial repeatword ugram)
                   flength 
                   mention speaker  question thanks]]
    (for [curr chats prev chats 
          :while (not= curr prev)
          :when (and (not (.equals "T-1" (curr :thread)))
                     (not (.equals "T-1" (prev :thread)))
                     (> blocksize (- (curr :timestamp) (prev :timestamp))))]
      (pairfeats featfuncs prev curr)
      )))

(defn gen-features-closure
  "should take all inputs such as unigrams, techwords, but leaving empty for now"
  [appendfn]
  (let [blocksize 129
        ugrambase (ustats/load-unigrams (str lp/ldir "unigrams.txt")) 
        ugram (:unigrams ugrambase)
        linuxwords (set (map #(.trim %) 
                             (.split 
                               (slurp 
                                 (str lp/ldir "mytechwords.dump")) "\n")))
        featfuncs (get-feats linuxwords ugram)
        ;my-accumulator (perline/accumulator2)
        ]
    (fn [inp]
      (let [prevchats (appendfn inp)]
              ;(println (str "curr1 " (peek prevchats)))
        (for [prev (pop prevchats) :let [curr (peek prevchats)]
              :while (not= curr prev)
              :when (and (not (.equals "T-1" (curr :thread)))
                         (not (.equals "T-1" (prev :thread)))
                         (> blocksize (- (curr :timestamp) (prev :timestamp))))]
          ;(pairfeats featfuncs prev curr)
          (assoc (pairfeats featfuncs prev curr) :tid (prev :ntid)))))))

(defn gen-features-closure-sbformat
  "should take all inputs such as unigrams, techwords, but leaving empty for now"
  [appendfn]
  (let [blocksize 129
        ugrambase (ustats/load-unigrams (str lp/ldir "unigrams.txt")) 
        ugram (:unigrams ugrambase)
        linuxwords (set (map #(.trim %) 
                             (.split 
                               (slurp 
                                 (str lp/ldir "mytechwords.dump")) "\n")))
        featfuncs (get-feats linuxwords ugram)]
    (fn [inp]
      (do ;(println (str "input to closure " inp))
      (let [prevchats (appendfn inp)]
              ;(println (str "curr1 " (count prevchats)))
        (for [prev (pop prevchats) :let [curr (peek prevchats)]
              :while (not= curr prev)
             :when (> blocksize (- (curr :timestamp) (prev :timestamp)))]
          (assoc (pairfeats-sbformat featfuncs prev curr) :tid (prev :ntid)))
        ;(pairfeats-sbformat featfuncs prev curr)
          ;(mapv :msgid [prev curr])
          )
        ))))

(defn gen-features-closure-old
  "should take all inputs such as unigrams, techwords, but leaving empty for now"
  []
  (let [blocksize 129
        ugrambase (ustats/load-unigrams (str lp/ldir "unigrams.txt")) 
        ugram (:unigrams ugrambase)
        linuxwords (set (map #(.trim %) 
                             (.split 
                               (slurp 
                                 (str lp/ldir "mytechwords.dump")) "\n")))
        featfuncs (get-feats linuxwords ugram)
        my-accumulator (perline/accumulator)]
    (fn [inp]
      (let [prevchats (my-accumulator inp)]
              ;(println (str "curr1 " (peek prevchats)))
        (for [prev (pop prevchats) :let [curr (peek prevchats)]
              :while (not= curr prev)
              :when (and (not (.equals "T-1" (curr :thread)))
                         (not (.equals "T-1" (prev :thread)))
                         (> blocksize (- (curr :timestamp) (prev :timestamp))))]
          ;(pairfeats featfuncs prev curr)
          (assoc (pairfeats featfuncs prev curr) :tid (prev :thread)))))))

(defn generate-features-perline
  [infile]
  (let [c1 (slurp infile)
        iseq (.split c1 "\n")
        acu (cp/generate-msgs-perline cp/parseline)  
        featfn (gen-features-closure)]
    (reduce into []  (remove empty? (map featfn (map acu iseq))))
    ))

;(def lwords (slurp "/home/kiran/sw/chat_dis/chat-dis/data/mytechwords.dump"))
;(def linuxwords (set (map #(.trim %) (.split lwords "\n"))))

(defn write-feats
  [infile outfile]
  (spit outfile
        (let [fe ;(generate-features infile)
              (generate-features-perline infile)
              ]
          (clojure.string/join "\n" 
                               ;(map #(str %2 " " %1)
                               (for [i fe :when (not (empty? i))]
                                 (clojure.string/join " "
                                                      (into [(:same i)]
                                                            (for [[k v] 
                                                                  (dissoc i :same) 
                                                                  ;:when (not= 0 v)
                                                                  ]
                                                              (str k " " v)
                                                              ;k
                                                              ))))
                               ;(iterate inc 1)
                               ))))

(def fkeys #{"dt_0" "dt_1" "neither_tech" "dt_2" "dt_3" "curr_q" "dt_4" "prev_q" 
             "dt_5" "dt_6" "dt_7" "prev_long" "dt_8" "curr_long" "dt_9" "prev_answer" 
             "curr_answer" "dt_10" "repeat_2" "dt_11" "one_tech" "repeat_3" 
             "repeat_4" "same_mention" "both_tech" "prev_mentions" "same_spk" 
             "curr_mentions" "curr_thx" "prev_thx" "curr_mentions_prev" 
             "curr_greet" "prev_mentions_curr" "prev_greet"})
;(def trainfile "/home/kiran/sw/chat_dis/chat-dis/IRC/dev/linux-dev-0X.annot")

(comment
(try
(write-feats (str lp/ldir "linux-dev-0X.annot")
             ;(str lp/ldir "trainv2.feat")
             "/tmp/tf2.txt"
             )
(write-feats (str lp/ldir  "linux-test-0.annot")
             (str lp/tmpdir "test.feat"))
(catch Exception e (.printStackTrace e)))


 
(time (count (generate-features-perline (str lp/ldir "linux-dev-0X.annot"))))
)



                                            