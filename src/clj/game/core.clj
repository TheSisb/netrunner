(ns game.core
  (:require [game.utils :refer [remove-once has? merge-costs zone make-cid to-keyword capitalize
                                costs-to-symbol vdissoc distinct-by]]
            [game.macros :refer [effect req msg]]
            [clojure.string :refer [split-lines split join]]))

(declare cards)

(def game-states (atom {}))

(defn card-def [card]
  (when-let [title (:title card)]
    (cards (.replace title "'" ""))))

(defn say [state side {:keys [user text]}]
  (let [author (or user (get-in @state [side :user]))]
    (swap! state update-in [:log] #(conj % {:user author :text text}))))

(defn system-msg [state side text]
  (let [username (get-in @state [side :user :username])]
    (say state side {:user "__system__" :text (str username " " text ".")})))

(declare prompt! forfeit trigger-event handle-end-run trash)

(defn pay [state side card & args]
  (let [costs (merge-costs (remove #(or (nil? %) (= % [:forfeit])) args))
        forfeit-cost (some #{[:forfeit] :forfeit} args)
        scored (get-in @state [side :scored])]
    (when (and (every? #(>= (- (get-in @state [side (first %)]) (last %)) 0) costs)
               (or (not forfeit-cost) (not (empty? scored))))
      (when forfeit-cost
           (if (= (count scored) 1)
             (forfeit state side (first scored))
             (prompt! state side card "Choose an Agenda to forfeit" scored
                      {:effect (effect (forfeit target))})))
      (not (doseq [c costs]
             (when (= (first c) :click)
               (trigger-event state side (if (= side :corp) :corp-spent-click :runner-spent-click) nil)
               (swap! state assoc-in [side :register :spent-click] true))
             (swap! state update-in [side (first c)] #(- (or % 0) (last c))))))))

(defn gain [state side & args]
  (doseq [r (partition 2 args)]
    (swap! state update-in [side (first r)] #(+ (or % 0) (last r)))))

(defn lose [state side & args]
  (doseq [r (partition 2 args)]
    (trigger-event state side (if (= side :corp) :corp-loss :runner-loss) r)
    (if (= (last r) :all)
      (swap! state assoc-in [side (first r)] 0)
      (swap! state update-in [side (first r)] #(max (- (or % 0) (last r)) 0)))))

(defn register-events [state side events card]
  (doseq [e events]
    (swap! state update-in [:events (first e)] #(conj % {:ability (last e) :card card}))))

(defn unregister-events [state side card]
  (doseq [e (:events (card-def card))]
    (swap! state update-in [:events (first e)]
           #(remove (fn [effect] (= (get-in effect [:card :cid]) (:cid card))) %))))

(defn desactivate
  ([state side card] (desactivate state side card nil))
  ([state side card keep-counter]
   (let [c (dissoc card :counter :current-strength :abilities :rezzed :special)
         c (if keep-counter c (dissoc c :advance-counter))]
     (when-let [leave-effect (:leave-play (card-def card))]
       (when (or (= (:side card) "Runner") (:rezzed card))
         (leave-effect state side card nil)))
     (when-let [prevent (:prevent (card-def card))]
               (doseq [dtype prevent]
                      (swap! state update-in [:damage :prevent dtype]
                             (fn [pv] (remove #(= (:cid %) (:cid card)) pv)))))
     (unregister-events state side card)
     (when-let [mu (:memoryunits card)]
       (gain state :runner :memory mu))
     c)))

(defn get-card [state {:keys [cid zone side host] :as card}]
  (if zone
    (if host
      (let [h (get-card state host)]
        (some #(when (= cid (:cid %)) %) (:hosted h)))
      (some #(when (= cid (:cid %)) %)
            (get-in @state (cons (to-keyword side) (map to-keyword zone)))))
    card))

(defn update! [state side {:keys [type zone cid host] :as card}]
  (if (= type "Identity")
    (swap! state assoc-in [side :identity] card)
    (if-let [h (get-card state host)]
      (let [[head tail] (split-with #(not= (:cid %) cid) (:hosted h))]
        (update! state side (assoc h :hosted (vec (concat head [card] (rest tail))))))
      (let [z (cons (to-keyword (:side card)) zone)
            [head tail] (split-with #(not= (:cid %) cid) (get-in @state z))]
        (when-not (empty? tail)
          (swap! state assoc-in z (vec (concat head [card] (rest tail)))))))))

(defn move-zone [state side server to]
  (let [from-zone (cons side (if (sequential? server) server [server]))
        to-zone (cons side (if (sequential? to) to [to]))]
    (swap! state assoc-in to-zone (concat (get-in @state to-zone)
                                          (zone to (get-in @state from-zone))))
    (swap! state assoc-in from-zone [])))

(defn move
  ([state side card to] (move state side card to nil))
  ([state side {:keys [zone cid host] :as card} to front]
   (let [zone (if host (map to-keyword (:zone host)) zone)]
     (when (and card (or host
                         (some #(when (= cid (:cid %)) %) (get-in @state (cons :runner (vec zone))))
                         (some #(when (= cid (:cid %)) %) (get-in @state (cons :corp (vec zone))))))
       (doseq [h (:hosted card)]
         (trash state side (update-in h [:zone] #(map to-keyword %))))
       (let [dest (if (sequential? to) (vec to) [to])
             c (if (and (= side :corp) (= (first dest) :discard) (:rezzed card))
                 (assoc card :seen true) card)
             c (if (and (#{:servers :rig :scored :current} (first zone))
                        (#{:hand :deck :discard} (first dest)))
                 (desactivate state side c) c)
             moved-card (assoc c :zone dest :host nil :hosted nil)]
         (if front
           (swap! state update-in (cons side dest) #(cons moved-card (vec %)))
           (swap! state update-in (cons side dest) #(conj (vec %) moved-card)))
         (doseq [s [:runner :corp]]
           (if host
             (when-let [host-card (some #(when (= (:cid host) (:cid %)) %)
                                        (get-in @state (cons s (vec zone))))]
               (update! state side (update-in host-card [:hosted]
                                              (fn [coll] (remove-once #(not= (:cid %) cid) coll)))))
             (swap! state update-in (cons s (vec zone))
                    (fn [coll] (remove-once #(not= (:cid %) cid) coll)))))
         (let [z (vec (cons :corp (butlast zone)))
               n (last z)]
           (when (and (number? n)
                      (empty? (get-in @state (conj z :content)))
                      (empty? (get-in @state (conj z :ices))))
             (when-let [run (:run @state)]
               (when (= (last (:server run)) n)
                 (handle-end-run state side)))
             (swap! state update-in [:corp :servers :remote] vdissoc n)
             (swap! state assoc-in [:corp :servers :remote]
                    (vec (map-indexed
                          (fn [i s]
                            (if (< i n) s
                                {:content (vec (for [c (:content s)]
                                                 (update-in c [:zone] #(assoc (vec %) 2 i))))
                                 :ices (vec (for [c (:ices s)]
                                              (update-in c [:zone] #(assoc (vec %) 2 i))))}))
                          (get-in @state [:corp :servers :remote]))))
             (doseq [s (drop n (get-in @state [:corp :servers :remote]))
                     c (concat (:content s) (:ices s))]
               (when (:rezzed c)
                 (when-let [events (:events (card-def c))]
                   (unregister-events state side c)
                   (register-events state side events c))))))
         moved-card)))))

(defn draw
  ([state side] (draw state side 1))
  ([state side n]
     (let [drawn (zone :hand (take n (get-in @state [side :deck])))]
       (swap! state update-in [side :hand] #(concat % drawn)))
     (swap! state update-in [side :deck] (partial drop n))
     (trigger-event state side (if (= side :corp) :corp-draw :runner-draw) n)))

(defn mill
  ([state side] (mill state side 1))
  ([state side n]
     (let [milled (zone :discard (take n (get-in @state [side :deck])))]
       (swap! state update-in [side :discard] #(concat % milled)))
     (swap! state update-in [side :deck] (partial drop n))))

(declare resolve-ability)

(defn show-prompt
  ([state side card msg choices f] (show-prompt state side card msg choices f nil))
  ([state side card msg choices f priority]
   (let [prompt (if (string? msg) msg (msg state side card nil))]
     (when (or (#{:credit :counter} choices) (> (count choices) 0))
       (swap! state update-in [side :prompt]
              (if priority
                #(cons {:msg prompt :choices choices :effect f :card card} (vec %))
                #(conj (vec %) {:msg prompt :choices choices :effect f :card card})))))))

(defn resolve-psi [state side card psi bet]
  (swap! state assoc-in [:psi side] bet)
  (let [opponent (if (= side :corp) :runner :corp)]
    (when-let [opponent-bet (get-in @state [:psi opponent])]
      (lose state opponent :credit opponent-bet)
      (system-msg state opponent (str "spends " opponent-bet " [Credits]"))
      (lose state side :credit bet)
      (system-msg state side (str "spends " bet " [Credits]"))
      (trigger-event state side :psi-game nil)
      (when-let [ability (if (= bet opponent-bet) (:equal psi) (:not-equal psi))]
        (resolve-ability state (:side card) ability card nil)))))

(defn psi-game [state side card psi]
  (swap! state assoc :psi {})
  (doseq [s [:corp :runner]]
    (show-prompt state s card (str "Choose an amount to spend for " (:title card))
                 (map #(str % " [Credits]") (range (min 3 (inc (get-in @state [s :credit])))))
                 #(resolve-psi state s card psi (Integer/parseInt (first (split % #" ")))))))

(defn prompt!
  ([state side card msg choices ability] (prompt! state side card msg choices ability nil))
  ([state side card msg choices ability priority]
    (show-prompt state side card msg choices #(resolve-ability state side ability card [%]) priority)))

(defn optional-ability [state side card msg ability targets]
  (show-prompt state side card msg ["Yes" "No"] #(if (= % "Yes")
                                                   (resolve-ability state side ability card targets)
                                                   (when-let [no-msg (:no-msg ability)]
                                                     (system-msg state side (:no-msg ability))))))

(defn resolve-trace [state side boost]
  (let [runner (:runner @state)
        {:keys [strength ability card]} (:trace @state)]
    (system-msg state :runner (str " spends " boost " [Credits] to increase link strength to "
                                   (+ (:link runner) boost)))
    (let [succesful (> strength (+ (:link runner) boost))
          ability (if succesful ability (:unsuccessful ability))]
      (resolve-ability state :corp ability card [strength (+ (:link runner) boost)])
      (trigger-event state :corp (if succesful :successful-trace :unsuccessful-trace)))
    (when-let [kicker (:kicker ability)]
      (when (>= strength (:min kicker))
        (resolve-ability state :corp kicker card [strength (+ (:link runner) boost)])))))

(defn init-trace [state side card {:keys [base] :as ability} boost]
  (let [base (if (fn? base) (base state side card nil) base) s (+ base boost)]
    (system-msg state :corp (str "uses " (:title card) " to initiate a trace with strength "
                                 s " (" base " + " boost " [Credits])"))
    (show-prompt state :runner card (str "Boost link strength?") :credit #(resolve-trace state side %))
    (swap! state assoc :trace {:strength s :ability ability :card card})
    (trigger-event state side :trace nil)))

(defn resolve-select [state side]
  (let [selected (get-in @state [side :selected 0])
        cards (map #(dissoc % :selected) (:cards selected))
        curprompt (first (get-in @state [side :prompt]))]
    (when-not (empty? cards)
      (doseq [card cards]
        (update! state side card))
      (resolve-ability state side (:ability selected) (:card curprompt) cards))
    (swap! state update-in [side :selected] #(vec (rest %)))
    (swap! state update-in [side :prompt] (fn [pr] (filter #(not= % curprompt) pr)))))

(defn show-select
  ([state side card ability] (show-select state side card ability nil))
  ([state side card ability priority]
   (let [ability (update-in ability [:choices :max] #(if (fn? %) (% state side card nil) %))]
     (swap! state update-in [side :selected]
            #(conj (vec %) {:ability (dissoc ability :choices) :req (get-in ability [:choices :req])
                            :max (get-in ability [:choices :max])}))
     (show-prompt state side card
                  (if-let [msg (:prompt ability)]
                    msg
                    (if-let [m (get-in ability [:choices :max])]
                      (str "Select up to " m " targets for " (:title card))
                      (str "Select a target for " (:title card))))
                  ["Done"] (fn [choice] (resolve-select state side)) priority))))

(defn resolve-ability [state side {:keys [counter-cost advance-counter-cost cost effect msg req once
                                          once-key optional prompt choices end-turn player psi trace
                                          not-distinct priority] :as ability}
                       {:keys [title cid counter advance-counter] :as card} targets]
  (when ability
    (when (and optional
               (not (get-in @state [(:once optional) (or (:once-key optional) cid)]))
               (or (not (:req optional)) ((:req optional) state side card targets)))
      (optional-ability state (or (:player optional) side) card (:prompt optional) optional targets))
    (when (and psi (or (not (:req psi)) ((:req psi) state side card targets)))
      (psi-game state side card psi))
    (when (and trace (or (not (:req trace)) ((:req trace) state side card targets)))
      (show-prompt state :corp card "Boost trace strength?" :credit
                   #(init-trace state :corp card trace %)))
    (when (and (not (get-in @state [once (or once-key cid)]))
               (or (not req) (req state side card targets)))
      (if choices
        (if (map? choices)
          (show-select state (or player side) card ability priority)
          (let [cs (if-not (fn? choices)
                     choices
                     (let [cards (choices state side card targets)]
                             (if not-distinct
                               cards (distinct-by :title cards))))]
            (prompt! state (or player side) card prompt cs (dissoc ability :choices) priority)))
        (when (and (or (not counter-cost) (<= counter-cost (or counter 0)))
                   (or (not advance-counter-cost) (<= advance-counter-cost (or advance-counter 0)))
                   (apply pay (concat [state side card] cost)))
          (let [c (-> card
                      (update-in [:advance-counter] #(- (or % 0) (or advance-counter-cost 0)))
                      (update-in [:counter] #(- (or % 0) (or counter-cost 0))))]
            (when (or counter-cost advance-counter-cost)
              (update! state side c)
              (when (= (:type card) "Agenda") (trigger-event state side :agenda-counter-spent card)))
            (when msg
              (let [desc (if (string? msg) msg (msg state side card targets))]
                (system-msg state (to-keyword (:side card))
                            (str "uses " title (when desc (str " to " desc))))))
            (when effect (effect state side c targets))
            (when end-turn
              (swap! state update-in [side :register :end-turn]
                     #(conj % {:ability end-turn :card card :targets targets}))))
          (when once (swap! state assoc-in [once (or once-key cid)] true)))))))

(defn handle-end-run [state side]
  (if-not (empty? (get-in @state [:runner :prompt]))
    (swap! state assoc-in [:run :ended] true)
    (do (let [server (get-in @state [:run :server])]
          (trigger-event state side :run-ends (first server))
          (when (get-in @state [:run :successful])
            (trigger-event state side :successful-run-ends (first server)))
          (when (get-in @state [:run :unsuccessful])
            (trigger-event state side :unsuccessful-run-ends (first server)))
          (swap! state assoc-in [:runner :rig :program]
                 (for [p (get-in @state [:runner :rig :program])]
                   (assoc p :current-strength nil)))
          (let [run-effect (get-in @state [:run :run-effect])]
            (when-let [end-run-effect (:end-run run-effect)]
              (resolve-ability state side end-run-effect (:card run-effect) [(first server)]))))
        (swap! state assoc :run nil))))

(defn add-prop [state side card key n]
  (update! state side (update-in card [key] #(+ (or % 0) n)))
  (when (= key :advance-counter)
    (trigger-event state side :advance card)))

(defn set-prop [state side card & args]
  (update! state side (apply assoc (cons card args))))

(defn resolve-prompt [state side {:keys [choice card] :as args}]
  (let [prompt (first (get-in @state [side :prompt]))
        choice (if (= (:choices prompt) :credit)
                 (min choice (get-in @state [side :credit]))
                 choice)]
    (when (= (:choices prompt) :credit)
      (pay state side card :credit choice))
    (when (= (:choices prompt) :counter)
      (add-prop state side (:card prompt) :counter (- choice)))
    (swap! state update-in [side :prompt] (fn [pr] (filter #(not= % prompt) pr)))
    ((:effect prompt) (or choice card))
    (when (empty? (get-in @state [:runner :prompt]))
      (when-let [run (:run @state)]
        (when (:ended run)
          (handle-end-run state :runner)))
      (swap! state dissoc :access))))

(defn trash-no-cost [state side]
  (when-let [card (:card (first (get-in @state [side :prompt])))]
    (trash state side card)
    (swap! state update-in [side :prompt] rest)
    (when-let [run (:run @state)]
      (when (and (:ended run) (empty? (get-in @state [:runner :prompt])) )
        (handle-end-run state :runner)))))

(defn select [state side {:keys [card] :as args}]
  (let [r (get-in @state [side :selected 0 :req])]
    (when (or (not r) (r card))
      (let [c (assoc card :selected (not (:selected card)))]
        (update! state side c)
        (if (:selected c)
          (swap! state update-in [side :selected 0 :cards] #(conj % c))
          (swap! state update-in [side :selected 0 :cards]
                 (fn [coll] (remove-once #(not= (:cid %) (:cid card)) coll))))
        (let [selected (get-in @state [side :selected 0])]
          (when (= (count (:cards selected)) (or (:max selected) 1))
            (resolve-select state side)))))))

(defn trigger-event [state side event & targets]
  (doseq [{:keys [ability] :as e} (get-in @state [:events event])]
    (when-let [card (get-card state (:card e))]
      (when (or (not (:req ability)) ((:req ability) state side card targets))
        (resolve-ability state side ability card targets)))))

(defn card-init [state side card]
  (let [cdef (card-def card)
        abilities (if (:recurring cdef)
                    (conj (:abilities cdef) {:msg "Take 1 [Recurring Credits]"})
                    (:abilities cdef))
        abilities (for [ab abilities]
                    (or (:label ab) (and (string? (:msg ab)) (capitalize (:msg ab))) ""))
        data (if-let [recurring (:recurring cdef)]
               (assoc (:data cdef) :counter recurring)
               (:data cdef))
        c (merge card data {:abilities abilities})]
    (when-let [recurring (:recurring cdef)]
      (register-events state side
                       {(if (= side :corp) :corp-turn-begins :runner-turn-begins)
                        {:effect (effect (set-prop card :counter recurring))}} c))
    (when-let [prevent (:prevent cdef)]
       (doseq [dtype prevent]
              (swap! state update-in [:damage :prevent dtype] #(conj % card))))
    (update! state side c)
    (resolve-ability state side cdef c nil)
    (when-let [events (:events cdef)]
      (register-events state side events c))
    (get-card state c)))

(defn rez-cost-bonus [state side n]
  (swap! state update-in [:bonus :cost] (fnil #(+ % n) 0)))

(defn rez-cost [state side {:keys [cost] :as card}]
  (-> cost
      (+ (or (get-in @state [:bonus :cost]) 0))
      (max 0)))

(defn trash-cost-bonus [state side n]
  (swap! state update-in [:bonus :trash] (fnil #(+ % n) 0)))

(defn trash-cost [state side {:keys [trash] :as card}]
  (-> trash
      (+ (or (get-in @state [:bonus :trash]) 0))
      (max 0)))

(defn damage-count [state side dtype n {:keys [unpreventable unboostable] :as args}]
  (-> n
      (+ (or (when (not unboostable) (get-in @state [:damage :damage-bonus dtype])) 0))
      (- (or (when (not unpreventable) (get-in @state [:damage :damage-prevent dtype])) 0))
      (max 0)))

(defn damage-bonus [state side dtype n]
  (swap! state update-in [:damage :damage-bonus dtype] (fnil #(+ % n) 0)))

(defn damage-prevent [state side dtype n]
  (swap! state update-in [:damage :damage-prevent dtype] (fnil #(+ % n) 0)))

(defn flatline [state]
  (system-msg state :runner "is flatlined"))

(defn resolve-damage [state side type n]
  (let [hand (get-in @state [:runner :hand])]
       (when (< (count hand) n)
             (flatline state))
       (when (= type :brain)
             (swap! state update-in [:runner :brain-damage] #(+ % n))
             (swap! state update-in [:runner :max-hand-size] #(- % n)))
       (doseq [c (take n (shuffle hand))]
              (trash state side c type))
       (trigger-event state side :damage type)))

(defn damage
  ([state side type n] (damage state side type n nil))
  ([state side type n {:keys [unpreventable unboostable] :as args}]
    (swap! state update-in [:damage :damage-bonus] dissoc type)
    (swap! state update-in [:damage :damage-prevent] dissoc type)
    (trigger-event state side :pre-damage type)
    (let [n (damage-count state side type n args)]
         (let [prevent (get-in @state [:damage :prevent type])]
              (if (and (not unpreventable) prevent (> (count prevent) 0))
                (do (system-msg state :runner "has the option to prevent damage")
                    (show-prompt
                      state :runner nil (str "Prevent any of the " n " " (name type) " damage?") ["Done"]
                      (fn [choice]
                          (let [prevent (get-in @state [:damage :damage-prevent type])]
                               (system-msg state :runner
                                           (if prevent
                                             (str "prevents " (if (= prevent Integer/MAX_VALUE) "all" prevent )
                                                  " " (name type) " damage")
                                             "will not prevent damage"))
                               (resolve-damage state side type (max 0 (- n (or prevent 0))))))))
                (resolve-damage state side type n))))))

(defn shuffle! [state side kw]
  (swap! state update-in [side kw] shuffle))

(defn change [state side {:keys [key delta]}]
  (let [kw (to-keyword key)]
    (swap! state update-in [side kw] (partial + delta))
    (system-msg state side
                (str "sets " (.replace key "-" " ") " to " (get-in @state [side kw])
                     " (" (if (> delta 0) (str "+" delta) delta) ")"))))

(defn create-deck [deck]
  (shuffle (mapcat #(map (fn [card]
                           (let [c (assoc card :cid (make-cid))]
                             (if-let [init (:init (card-def c))] (merge c init) c)))
                         (repeat (:qty %) (:card %)))
                   (:cards deck))))

(defn init-game [{:keys [players gameid] :as game}]
  (let [corp (some #(when (= (:side %) "Corp") %) players)
        runner (some #(when (= (:side %) "Runner") %) players)
        corp-deck (create-deck (:deck corp))
        runner-deck (create-deck (:deck runner))
        corp-identity (or (get-in corp [:deck :identity]) {:side "Corp" :type "Identity"})
        runner-identity (or (get-in runner [:deck :identity]) {:side "Runner" :type "Identity"})
        state (atom
               {:gameid gameid :log [] :active-player :runner :end-turn true
                :corp {:user (:user corp) :identity corp-identity
                       :deck (zone :deck (drop 5 corp-deck))
                       :hand (zone :hand (take 5 corp-deck))
                       :discard [] :scored [] :rfg [] :play-area []
                       :servers {:hq {} :rd{} :archives {} :remote []}
                       :click 0 :credit 5 :bad-publicity 0 :agenda-point 0 :max-hand-size 5
                       :click-per-turn 3 :agenda-point-req 7 :keep false}
                :runner {:user (:user runner) :identity runner-identity
                         :deck (zone :deck (drop 5 runner-deck))
                         :hand (zone :hand (take 5 runner-deck))
                         :discard [] :scored [] :rfg [] :play-area []
                         :rig {:program [] :resource [] :hardware []}
                         :click 0 :credit 5 :memory 4 :link 0 :tag 0 :agenda-point 0 :max-hand-size 5
                         :hq-access 1 :rd-access 1
                         :brain-damage 0 :click-per-turn 4 :agenda-point-req 7 :keep false}})]
    (card-init state :corp corp-identity)
    (card-init state :runner runner-identity)
    (swap! game-states assoc gameid state)))

(def reset-value
  {:corp {:credit 5 :bad-publicity 0 :max-hand-size 5}
   :runner {:credit 5 :link 0 :memory 4 :max-hand-size 5}})

(defn shuffle-into-deck [state side & args]
  (let [player (side @state)
        deck (shuffle (reduce concat (:deck player) (for [p args] (zone :deck (p player)))))]
    (swap! state assoc-in [side :deck] deck))
  (doseq [p args]
    (swap! state assoc-in [side p] [])))

(defn mulligan [state side args]
  (shuffle-into-deck state side :hand)
  (draw state side 5)
  (let [card (get-in @state [side :identity])]
    (when-let [cdef (card-def card)]
      (when-let [mul (:mulligan cdef)]
        (mul state side card nil))))
  (swap! state assoc-in [side :keep] true)
  (system-msg state side "takes a mulligan"))

(defn keep-hand [state side args]
  (swap! state assoc-in [side :keep] true)
  (system-msg state side "keeps his or her hand"))

(defn gain-agenda-point [state side n]
  (gain state side :agenda-point n)
  (when (>= (get-in @state [side :agenda-point]) (get-in @state [side :agenda-point-req]))
    (system-msg state side "wins the game")))

(defn trash [state side {:keys [zone] :as card} & targets]
  (when (not= (last zone) :current)
    (trigger-event state side :trash card targets))
  (let [cdef (card-def card)
        moved-card (move state (to-keyword (:side card)) card :discard false)]
    (when-let [trash-effect (:trash-effect cdef)]
      (resolve-ability state side trash-effect moved-card targets))))

(defn trash-cards [state side cards]
  (doseq [c cards] (trash state side c)))

(defn pump
  ([state side card n] (pump state side card n false))
  ([state side {:keys [strength current-strength] :as card} n all-run]
     (let [c (if current-strength
               card
               (assoc card :current-strength strength :all-run all-run))]
       (update! state side (update-in c [:current-strength] #(+ % n))))))

(defn score [state side args]
  (let [card (or (:card args) args)]
    (when (>= (:advance-counter card) (:advancementcost card))
      (let [moved-card (move state :corp card :scored)
            c (card-init state :corp moved-card)]
        (system-msg state :corp (str "scores " (:title c) " and gains " (:agendapoints c)
                                    " agenda point" (when (> (:agendapoints c) 1) "s")))
        (swap! state update-in [:corp :register :scored-agenda] #(+ (or % 0) (:agendapoints c)))
        (gain-agenda-point state :corp (:agendapoints c))
        (set-prop state :corp c :advance-counter 0)
        (when-let [current (first (get-in @state [:runner :current]))]
          (say state side {:user "__system__" :text (str (:title current) " is trashed.")})
          (trash state side current))
        (trigger-event state :corp :agenda-scored (assoc c :advance-counter 0))))))

(defn steal [state side card]
  (let [c (move state :runner card :scored)]
    (resolve-ability state :runner (:stolen (card-def c)) c nil)
    (system-msg state :runner (str "steals " (:title c) " and gains " (:agendapoints c)
                                   " agenda point" (when (> (:agendapoints c) 1) "s")))
    (swap! state update-in [:runner :register :stole-agenda] #(+ (or % 0) (:agendapoints c)))
    (gain-agenda-point state :runner (:agendapoints c))
    (set-prop state :runner c :advance-counter 0)
    (when-let [current (first (get-in @state [:corp :current]))]
      (say state side {:user "__system__" :text (str (:title current) " is trashed.")})
      (trash state side current))
    (trigger-event state :runner :agenda-stolen c)))

(defn card->server [state card]
  (let [z (:zone card)]
       (if (= (second z) :remote)
         (nth (get-in @state [:corp :servers :remote]) (nth z 2))
         (get-in @state [:corp :servers (second z)]))))

(defn server->zone [state server]
  (if (sequential? server)
    (vec (cons :servers server))
    (case server
      "HQ" [:servers :hq]
      "R&D" [:servers :rd]
      "Archives" [:servers :archives]
      "New remote" [:servers :remote (count (get-in @state [:corp :servers :remote]))]
      [:servers :remote (-> (split server #" ") last Integer/parseInt)])))

(defn run
  ([state side server] (run state side server nil nil))
  ([state side server run-effect card]
     (when-not (get-in @state [:runner :register :cannot-run])
       (let [s (cond
                (= server "HQ") [:hq]
                (= server "R&D") [:rd]
                (= server "Archives") [:archives]
                (keyword? server) [server]
                :else [:remote (-> (split server #" ") last Integer/parseInt)])
             ices (get-in @state (concat [:corp :servers] s [:ices]))]
         (swap! state assoc :per-run nil
                :run {:server s :position (count ices) :ices ices :access-bonus 0
                      :run-effect (assoc run-effect :card card)})
         (swap! state update-in [:runner :register :made-run] #(conj % (first s)))
         (trigger-event state :runner :run s)))))

(defn handle-access [state side cards]
  (swap! state assoc :access true)
  (swap! state update-in [:bonus] dissoc :trash)
  (doseq [c cards]
    (let [cdef (card-def c)
          c (assoc c :seen true)]
      (when-let [name (:title c)]
        (when-let [access-effect (:access cdef)]
          (resolve-ability state (to-keyword (:side c)) access-effect c nil))
        (when (not= (:zone c) [:discard])
          (trigger-event state side :pre-trash c)
          (if-let [trash-cost (trash-cost state side c)]
            (let [card (assoc c :seen true)]
              (optional-ability state :runner card (str "Pay " trash-cost "[Credits] to trash " name "?")
                                {:cost [:credit trash-cost]
                                 :effect (effect (trash card)
                                                 (system-msg (str "pays " trash-cost " [Credits] to trash "
                                                                  (:title card))))} nil))
            (when-not (= (:type c) "Agenda")
              (prompt! state :runner c (str "You accessed " (:title c)) ["OK"] {}))))
        (when (= (:type c) "Agenda")
          (if-let [cost (:steal-cost (card-def c))]
            (optional-ability state :runner c (str "Pay " (costs-to-symbol cost) " to steal " name "?")
                              {:cost cost
                               :effect (effect (system-msg (str "pays " (costs-to-symbol cost)
                                                                " to steal " (:title c)))
                                               (steal c))} nil)
            (when (or (not (:steal-req cdef)) ((:steal-req cdef) state :runner c nil))
              (steal state :runner c))))
        (trigger-event state side :access c)))))

(defn max-access [state side n]
  (swap! state assoc-in [:run :max-access] n))

(defn access-count [state side kw]
  (let [run (:run @state)
        accesses (+ (get-in @state [:runner kw]) (:access-bonus run))]
    (if-let [max-access (:max-access run)]
      (min max-access accesses) accesses)))

(defmulti access (fn [state side server] (first server)))

(defmethod access :hq [state side server]
  (concat (take (access-count state side :hq-access) (shuffle (get-in @state [:corp :hand])))
          (get-in @state [:corp :servers :hq :content])))

(defmethod access :rd [state side server]
  (concat (take (access-count state side :rd-access) (get-in @state [:corp :deck]))
          (get-in @state [:corp :servers :rd :content])))

(defmethod access :archives [state side server]
  (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
  (concat (get-in @state [:corp :discard]) (get-in @state [:corp :servers :archives :content])))

(defmethod access :remote [state side server]
  (get-in @state [:corp :servers :remote (last server) :content]))

(defn access-bonus [state side n]
  (swap! state update-in [:run :access-bonus] #(+ % n)))

(defn do-access [state side server]
  (let [cards (access state side server)]
    (when-not (empty? cards)
      (if (= (first server) :rd)
        (let [n (count cards)]
          (system-msg state side (str "accesses " n " card" (when (> n 1) "s"))))
        (system-msg state side (str "accesses " (join ", "(map :title cards)))))
      (handle-access state side cards)))
  (handle-end-run state side))

(defn replace-access [state side ability card]
  (resolve-ability state side ability card nil)
  (handle-end-run state side))

(defn successful-run [state side args]
  (when-let [successful-run-effect (get-in @state [:run :run-effect :successful-run])]
    (resolve-ability state side successful-run-effect (:card successful-run-effect) nil))
  (let [server (get-in @state [:run :server])]
    (swap! state update-in [:runner :register :successful-run] #(conj % (first server)))
    (swap! state assoc-in [:run :successful] true)
    (trigger-event state side :successful-run (first server))
    (let [card (get-in @state [:run :run-effect :card])]
      (if-let [replace-effect (get-in @state [:run :run-effect :replace-access])]
        (if (:mandatory replace-effect)
          (replace-access state side replace-effect card)
          (swap! state update-in [side :prompt]
                 (fn [p]
                   (conj (vec p) {:msg "Use Run ability instead of accessing cards?"
                                  :choices ["Run ability" "Access"]
                                  :effect #(if (= % "Run ability")
                                             (replace-access state side replace-effect card)
                                             (do-access state side server))}))))
        (do-access state side server)))))

(defn end-run [state side]
  (let [server (first (get-in @state [:run :server]))]
    (swap! state update-in [:runner :register :unsuccessful-run] #(conj % server))
    (swap! state assoc-in [:run :unsuccessful] true)
    (trigger-event state side :unsuccessful-run)
    (handle-end-run state side)))

(defn no-action [state side args]
  (swap! state assoc-in [:run :no-action] true)
  (system-msg state side "has no further action"))

(defn continue [state side args]
  (when (get-in @state [:run :no-action])
    (swap! state update-in [:run :position] dec)
    (swap! state assoc-in [:run :no-action] false)
    (swap! state assoc-in [:runner :rig :program]
           (for [p (get-in @state [:runner :rig :program])]
             (if (or (not (:current-strength p)) (:all-run p))
               p (assoc p :current-strength nil))))
    (system-msg state side "continues the run")))

(defn play-ability [state side {:keys [card ability targets] :as args}]
  (let [cdef (card-def card)
        abilities (:abilities cdef)
        ab (if (= ability (count abilities))
             {:msg "take 1 [Recurring Credits]" :counter-cost 1 :effect (effect (gain :credit 1))}
             (get-in cdef [:abilities ability]))]
    (resolve-ability state side ab card targets)))

(defn start-turn [state side args]
  (system-msg state side (str "started his or her turn"))
  (swap! state assoc :active-player side :per-turn nil :end-turn false)
  (swap! state assoc-in [side :register] nil)
  (swap! state assoc-in [side :click] (get-in @state [side :click-per-turn]))
  (trigger-event state side (if (= side :corp) :corp-turn-begins :runner-turn-begins))
  (when (= side :corp) (draw state :corp)))

(defn end-turn [state side args]
  (let [max-hand-size (get-in @state [side :max-hand-size])]
    (when (<= (count (get-in @state [side :hand])) max-hand-size)
      (system-msg state side (str "is ending his or her turn"))
      (if (= side :runner)
        (do (when (< (get-in @state [:runner :max-hand-size]) 0)
              (flatline state))
            (trigger-event state side :runner-turn-ends))
        (trigger-event state side :corp-turn-ends))
      (doseq [a (get-in @state [side :register :end-turn])]
        (resolve-ability state side (:ability a) (:card a) (:targets a)))
      (swap! state assoc :end-turn true))))

(defn purge [state side]
  (doseq [card (concat (get-in @state [:runner :rig :program])
                       (get-in @state [:runner :rig :resource])
                       (get-in @state [:runner :rig :hardware])
                       (->> (get-in @state [:corp :servers]) seq flatten (mapcat :ices) (mapcat :hosted)))]
    (when (or (has? card :subtype "Virus") (= (:counter-type card) "Virus"))
      (set-prop state :runner card :counter 0)))
  (trigger-event state side :purge))

(defn get-virus-counters [state side card]
   (let [hiveminds (filter #(= (:title %) "Hivemind") (get-in @state [:runner :rig :program]))]
        (reduce + (map :counter (cons card hiveminds)))))

(defn play-instant
  ([state side card] (play-instant state side card nil))
  ([state side {:keys [title] :as card} {:keys [targets extra-cost no-additional-cost]}]
     (let [cdef (card-def card)
           additional-cost (if (has? card :subtype "Double")
                             (concat (:additional-cost cdef) [:click 1])
                             (:additional-cost cdef))]
       (when (and (if-let [req (:req cdef)]
                    (req state side card targets) true)
                  (not (and (has? card :subtype "Priority")
                            (get-in @state [side :register :spent-click])))
                  (pay state side card :credit (:cost card) extra-cost
                       (when-not no-additional-cost additional-cost)))
         (let [c (move state side (assoc card :seen true) :play-area)]
           (system-msg state side (str "plays " title))
           (trigger-event state side (if (= side :corp) :play-operation :play-event) c)
           (resolve-ability state side cdef card nil)
           (if (has? c :subtype "Current")
             (do (doseq [s [:corp :runner]]
                   (when-let [current (first (get-in @state [s :current]))]
                     (say state side {:user "__system__" :text (str (:title current) " is trashed.")})
                     (trash state side current)))
                 (let [moved-card (move state side (first (get-in @state [side :play-area])) :current)]
                   (card-init state side moved-card)))
             (move state side (first (get-in @state [side :play-area])) :discard)))))))

(defn in-play? [state card]
  (let [dest (when (= (:side card) "Runner")
               (get-in @state [:runner :rig (to-keyword (:type card))]))]
    (some #(= (:title %) (:title card)) dest)))

(defn host [state side card {:keys [zone cid] :as target}]
  (swap! state update-in (cons side (vec zone)) (fn [coll] (remove-once #(not= (:cid %) cid) coll)))
  (let [c (assoc target :host (update-in card [:zone] #(map to-keyword %)))]
    (update! state side (update-in card [:hosted] #(conj % c)))
    c))

(defn runner-install
  ([state side card] (runner-install state side card nil))
  ([state side {:keys [title type cost memoryunits uniqueness] :as card}
    {:keys [extra-cost no-cost host-card] :as params}]
   (if-let [hosting (and (not host-card) (:hosting (card-def card)))]
     (resolve-ability state side
                      {:choices hosting
                       :effect (effect (runner-install card (assoc params :host-card target)))} card nil)
     (let [cost (if no-cost 0 cost)]
       (when (and (or (not uniqueness) (not (in-play? state card)))
                  (if-let [req (:req (card-def card))]
                    (req state side card nil) true)
                  (pay state side card :credit cost (when memoryunits [:memory memoryunits]) extra-cost))
         (let [c (if host-card
                   (host state side host-card card)
                   (move state side card [:rig (to-keyword type)]))
               installed-card (card-init state side c)]
           (system-msg state side (str "installs " title
                                       (when host-card (str " on " (:title host-card)))
                                       (when no-cost " at no cost")))
           (trigger-event state side :runner-install installed-card)))))))

(defn server-list [state card]
  (let [remotes (cons "New remote" (for [i (range (count (get-in @state [:corp :servers :remote])))]
                                     (str "Server " i)))]
    (if (#{"Asset" "Agenda"} (:type card))
        remotes
        (concat ["HQ" "R&D" "Archives"] remotes))))

(defn rez
  ([state side card] (rez state side card nil))
  ([state side card {:keys [no-cost] :as args}]
     (swap! state update-in [:bonus] dissoc :cost)
     (trigger-event state side :pre-rez card)
     (when (or (#{"Asset" "ICE" "Upgrade"} (:type card)) (:install-rezzed (card-def card)))
       (let [cdef (card-def card) cost (rez-cost state side card)]
         (when (or no-cost (pay state side card :credit cost (:additional-cost cdef)))
           (card-init state side (assoc card :rezzed true))
           (system-msg state side (str "rez " (:title card) (when no-cost " at no cost")))
           (trigger-event state side :rez card))))))

(defn corp-install
  ([state side card server] (corp-install state side card server nil))
  ([state side card server {:keys [extra-cost no-install-cost rezzed] :as args}]
     (if-not server
       (prompt! state side card (str "Choose a server to install " (:title card))
                (server-list state card) {:effect (effect (corp-install card target args))})
       (do (when (= server "New remote")
             (trigger-event state side :server-created card))
           (let [c (assoc card :advanceable (:advanceable (card-def card)))
                 slot (conj (server->zone state server) (if (= (:type c) "ICE") :ices :content))
                 dest-zone (get-in @state (cons :corp slot))
                 install-cost (if (and (= (:type c) "ICE") (not no-install-cost))
                                (count dest-zone) 0)
                 rezzed (or rezzed (:install-rezzed (card-def card)))]
             (when (and (not (and (has? c :subtype "Region")
                                  (some #(has? % :subtype "Region") dest-zone)))
                        (pay state side card extra-cost :credit install-cost))
               (when (#{"Asset" "Agenda"} (:type c))
                 (when-let [prev-card (some #(when (#{"Asset" "Agenda"} (:type %)) %) dest-zone)]
                   (system-msg state side (str "trashes " (if (:rezzed prev-card)
                                                            (:title prev-card) "a card") " in " server))
                   (trash state side prev-card)))
               (let [card-name (if (or rezzed (:rezzed c)) (:title card) "a card")]
                 (if (> install-cost 0)
                   (system-msg state side (str "pays " install-cost " [Credits] to install "
                                               card-name " in " server))
                   (system-msg state side (str "installs " card-name " in " server))))
               (let [moved-card (move state side c slot)]
                 (trigger-event state side :corp-install moved-card)
                 (when rezzed (rez state side moved-card {:no-cost true})))))))))

(defn play [state side {:keys [card server]}]
  (case (:type card)
    ("Event" "Operation") (play-instant state side card {:extra-cost [:click 1]})
    ("Hardware" "Resource" "Program") (runner-install state side card {:extra-cost [:click 1]})
    ("ICE" "Upgrade" "Asset" "Agenda") (corp-install state side card server {:extra-cost [:click 1]}))
  (trigger-event state side :play card))

(defn derez [state side card]
  (system-msg state side (str "derez " (:title card)))
  (update! state :corp (desactivate state :corp card true))
  (trigger-event state side :derez card))

(defn advance [state side {:keys [card]}]
  (when (pay state side card :click 1 :credit 1)
    (system-msg state side "advances a card")
    (add-prop state side card :advance-counter 1)))

(defn forfeit [state side card]
  (system-msg state side (str "forfeit " (:title card)))
  (gain state side :agenda-point (- (:agendapoints card)))
  (move state :corp card :rfg))

(defn expose [state side target]
  (system-msg state side (str "exposes " (:title target)))
  (when-let [ability (:expose (card-def target))]
    (resolve-ability state side ability target nil))
  (trigger-event state side :expose target))

(defn prevent-run [state side]
  (swap! state assoc-in [:runner :register :cannot-run] true))

(defn prevent-jack-out [state side]
  (swap! state assoc-in [:run :cannot-jack-out] true))

(defn move-card [state side {:keys [card server]}]
  (let [c (update-in card [:zone] #(map to-keyword %))
        label (if (or (= (:side c) "Runner") (:rezzed c) (:seen c)
                      (= (last (:zone c)) :deck))
                (:title c) "a card")
        s (if (#{"HQ" "R&D" "Archives"} server) :corp :runner)]
    (case server
      ("Heap" "Archives")
      (do (trash state s c)
          (system-msg state side (str "trashes " label)))
      ("HQ" "Grip")
      (do (move state s (dissoc c :seen :rezzed) :hand false)
          (system-msg state side (str "moves " label " to " server)))
      ("Stack" "R&D")
      (do (move state s (dissoc c :seen :rezzed) :deck true)
          (system-msg state side (str "moves " label " to the top of " server)))
      nil)))

(defn click-run [state side {:keys [server] :as args}]
  (when (and (not (get-in @state [:runner :register :cannot-run])) (pay state :runner nil :click 1))
    (system-msg state :runner (str "makes a run on " server))
    (run state side server)))

(defn click-draw [state side args]
  (when (pay state side nil :click 1)
    (system-msg state side "spends [Click] to draw a card")
    (draw state side)
    (trigger-event state side (if (= side :corp) :corp-click-draw :runner-click-draw))))

(defn click-credit [state side args]
  (when (pay state side nil :click 1)
    (system-msg state side "spends [Click] to gain 1 [Credits]")
    (gain state side :credit 1)
    (trigger-event state side (if (= side :corp) :corp-click-credit :runner-click-credit))))

(defn do-purge [state side args]
  (when (pay state side nil :click 3)
    (system-msg state side "purges viruses")
    (purge state side)))

(defn remove-tag [state side args]
  (when (pay state side nil :click 1 :credit 2 :tag 1)
    (system-msg state side "spend [Click] and 2 [Credits] to remove 1 tag")))

(defn jack-out [state side args]
  (end-run state side)
  (system-msg state side "jacks out")
  (trigger-event state side :jack-out))

(defn shuffle-deck [state side {:keys [close] :as args}]
  (swap! state update-in [side :deck] shuffle)
  (if close
    (system-msg state side "stops looking at his deck and shuffles it")
    (system-msg state side "shuffles his deck")))

(load "cards")
