;; Copyright 2020-2023 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.code.view
  (:require [cljfx.api :as fx]
            [cljfx.ext.list-view :as fx.ext.list-view]
            [cljfx.fx.button :as fx.button]
            [cljfx.fx.h-box :as fx.h-box]
            [cljfx.fx.label :as fx.label]
            [cljfx.fx.list-cell :as fx.list-cell]
            [cljfx.fx.list-view :as fx.list-view]
            [cljfx.fx.region :as fx.region]
            [cljfx.fx.stack-pane :as fx.stack-pane]
            [cljfx.fx.v-box :as fx.v-box]
            [cljfx.lifecycle :as fx.lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code-completion :as code-completion]
            [editor.code.data :as data]
            [editor.code.resource :as r]
            [editor.code.util :refer [split-lines]]
            [editor.error-reporting :as error-reporting]
            [editor.fxui :as fxui]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.keymap :as keymap]
            [editor.lsp :as lsp]
            [editor.markdown :as markdown]
            [editor.notifications :as notifications]
            [editor.prefs :as prefs]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.ui.bindings :as b]
            [editor.ui.fuzzy-choices :as fuzzy-choices]
            [editor.ui.fuzzy-choices-popup :as popup]
            [editor.util :as eutil]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [internal.util :as util]
            [schema.core :as s]
            [service.smoke-log :as slog]
            [util.coll :refer [pair]])
  (:import [com.defold.control ListView]
           [com.sun.javafx.font FontResource FontStrike PGFont]
           [com.sun.javafx.geom.transform BaseTransform]
           [com.sun.javafx.perf PerformanceTracker]
           [com.sun.javafx.scene.text FontHelper]
           [com.sun.javafx.tk Toolkit]
           [com.sun.javafx.util Utils]
           [editor.code.data Cursor CursorRange GestureInfo LayoutInfo Rect]
           [java.util Collection]
           [java.util.regex Pattern]
           [javafx.beans.binding ObjectBinding]
           [javafx.beans.property Property SimpleBooleanProperty SimpleDoubleProperty SimpleObjectProperty SimpleStringProperty]
           [javafx.beans.value ChangeListener]
           [javafx.event Event EventHandler]
           [javafx.geometry HPos Point2D Rectangle2D VPos]
           [javafx.scene Node Parent Scene]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Button CheckBox Tab TextField]
           [javafx.scene.input Clipboard DataFormat InputMethodEvent InputMethodRequests KeyCode KeyEvent MouseButton MouseDragEvent MouseEvent ScrollEvent]
           [javafx.scene.layout ColumnConstraints GridPane Pane Priority]
           [javafx.scene.paint Color LinearGradient Paint]
           [javafx.scene.shape Rectangle]
           [javafx.scene.text Font FontSmoothingType Text TextAlignment]
           [javafx.stage PopupWindow Screen Stage]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defonce ^:private default-font-size 12.0)

(defprotocol GutterView
  (gutter-metrics [this lines regions glyph-metrics] "A two-element vector with a rounded double representing the width of the gutter and another representing the margin on each side within the gutter.")
  (draw-gutter! [this gc gutter-rect layout font color-scheme lines regions visible-cursors] "Draws the gutter into the specified Rect."))

(defrecord CursorRangeDrawInfo [type fill stroke cursor-range])

(defn- cursor-range-draw-info [type fill stroke cursor-range]
  {:pre [(case type (:range :squiggle :underline :word) true false)
         (or (nil? fill) (instance? Paint fill))
         (or (nil? stroke) (instance? Paint stroke))
         (instance? CursorRange cursor-range)]}
  (->CursorRangeDrawInfo type fill stroke cursor-range))

(def ^:private *performance-tracker (atom nil))
(def ^:private undo-groupings #{:navigation :newline :selection :typing})
(g/deftype ColorScheme [[(s/one s/Str "pattern") (s/one Paint "paint")]])
(g/deftype CursorRangeDrawInfos [CursorRangeDrawInfo])
(g/deftype FocusState (s/->EnumSchema #{:not-focused :semi-focused :input-focused}))
(g/deftype GutterMetrics [(s/one s/Num "gutter-width") (s/one s/Num "gutter-margin")])
(g/deftype HoveredElement {:type s/Keyword s/Keyword s/Any})
(g/deftype MatchingBraces [[(s/one r/TCursorRange "brace") (s/one r/TCursorRange "counterpart")]])
(g/deftype UndoGroupingInfo [(s/one (s/->EnumSchema undo-groupings) "undo-grouping") (s/one s/Symbol "opseq")])
(g/deftype ResizeReference (s/enum :bottom :top))
(g/deftype VisibleWhitespace (s/enum :all :none :rogue))
(g/deftype CompletionState {:enabled s/Bool
                            (s/optional-key :showing) s/Bool
                            (s/optional-key :completions) [s/Any]
                            (s/optional-key :selected-index) s/Any
                            (s/optional-key :show-doc) s/Bool
                            (s/optional-key :project) s/Int})

(defn- boolean->visible-whitespace [visible?]
  (if visible? :all :rogue))

(defn- enable-performance-tracker! [scene]
  (reset! *performance-tracker (PerformanceTracker/getSceneTracker scene)))

(defn- mime-type->DataFormat
  ^DataFormat [^String mime-type]
  (or (DataFormat/lookupMimeType mime-type)
      (DataFormat. (into-array String [mime-type]))))

(extend-type Clipboard
  data/Clipboard
  (has-content? [this mime-type] (.hasContent this (mime-type->DataFormat mime-type)))
  (get-content [this mime-type] (.getContent this (mime-type->DataFormat mime-type)))
  (set-content! [this representation-by-mime-type]
    (.setContent this (into {}
                            (map (fn [[mime-type representation]]
                                   [(mime-type->DataFormat mime-type) representation]))
                            representation-by-mime-type))))

(def ^:private ^:const min-cached-char-width
  (double (inc Byte/MIN_VALUE)))

(def ^:private ^:const max-cached-char-width
  (double Byte/MAX_VALUE))

(defn- make-char-width-cache [^FontStrike font-strike]
  (let [cache (byte-array (inc (int Character/MAX_VALUE)) Byte/MIN_VALUE)]
    (fn get-char-width [^Character character]
      (let [ch (unchecked-char character)
            i (unchecked-int ch)
            cached-width (aget cache i)]
        (if (= cached-width Byte/MIN_VALUE)
          (let [width (Math/floor (.getCharAdvance font-strike ch))]
            (when (and (<= min-cached-char-width width)
                       (<= width max-cached-char-width))
              (aset cache i (byte width)))
            width)
          cached-width)))))

(defrecord GlyphMetrics [char-width-cache ^double line-height ^double ascent]
  data/GlyphMetrics
  (ascent [_this] ascent)
  (line-height [_this] line-height)
  (char-width [_this character] (char-width-cache character)))

(defn make-glyph-metrics
  ^GlyphMetrics [^Font font ^double line-height-factor]
  (let [font-loader (.getFontLoader (Toolkit/getToolkit))
        font-metrics (.getFontMetrics font-loader font)
        font-strike (.getStrike ^PGFont (FontHelper/getNativeFont font)
                                BaseTransform/IDENTITY_TRANSFORM
                                FontResource/AA_GREYSCALE)
        line-height (Math/ceil (* (inc (.getLineHeight font-metrics)) line-height-factor))
        ascent (Math/ceil (* (.getAscent font-metrics) line-height-factor))]
    (->GlyphMetrics (make-char-width-cache font-strike) line-height ascent)))

(def ^:private default-editor-color-scheme
  (let [^Color foreground-color (Color/valueOf "#DDDDDD")
        ^Color background-color (Color/valueOf "#27292D")
        ^Color selection-background-color (Color/valueOf "#4E4A46")
        ^Color execution-marker-color (Color/valueOf "#FBCE2F")
        ^Color execution-marker-frame-color (.deriveColor execution-marker-color 0.0 1.0 1.0 0.5)]
    [["editor.foreground" foreground-color]
     ["editor.background" background-color]
     ["editor.cursor" Color/WHITE]
     ["editor.selection.background" selection-background-color]
     ["editor.selection.background.inactive" (.deriveColor selection-background-color 0.0 0.0 0.75 1.0)]
     ["editor.selection.occurrence.outline" (Color/valueOf "#A2B0BE")]
     ["editor.tab.trigger.word.outline" (Color/valueOf "#A2B0BE")]
     ["editor.find.term.occurrence" (Color/valueOf "#60C1FF")]
     ["editor.execution-marker.current" execution-marker-color]
     ["editor.execution-marker.frame" execution-marker-frame-color]
     ["editor.gutter.foreground" (Color/valueOf "#A2B0BE")]
     ["editor.gutter.background" background-color]
     ["editor.gutter.cursor.line.background" (Color/valueOf "#393C41")]
     ["editor.gutter.breakpoint" (Color/valueOf "#AD4051")]
     ["editor.gutter.execution-marker.current" execution-marker-color]
     ["editor.gutter.execution-marker.frame" execution-marker-frame-color]
     ["editor.gutter.shadow" (LinearGradient/valueOf "to right, rgba(0, 0, 0, 0.3) 0%, transparent 100%")]
     ["editor.indentation.guide" (.deriveColor foreground-color 0.0 1.0 1.0 0.1)]
     ["editor.matching.brace" (Color/valueOf "#A2B0BE")]
     ["editor.minimap.shadow" (LinearGradient/valueOf "to left, rgba(0, 0, 0, 0.2) 0%, transparent 100%")]
     ["editor.minimap.viewed.range" (Color/valueOf "#393C41")]
     ["editor.scroll.tab" (.deriveColor foreground-color 0.0 1.0 1.0 0.15)]
     ["editor.scroll.tab.hovered" (.deriveColor foreground-color 0.0 1.0 1.0 0.5)]
     ["editor.whitespace.space" (.deriveColor foreground-color 0.0 1.0 1.0 0.2)]
     ["editor.whitespace.tab" (.deriveColor foreground-color 0.0 1.0 1.0 0.1)]
     ["editor.whitespace.rogue" (Color/valueOf "#FBCE2F")]]))

(defn make-color-scheme [ordered-paints-by-pattern]
  (into []
        (util/distinct-by first)
        (concat ordered-paints-by-pattern
                default-editor-color-scheme)))

(def ^:private code-color-scheme
  (make-color-scheme
    [["comment" (Color/valueOf "#B0B0B0")]
     ["string" (Color/valueOf "#FBCE2F")]
     ["numeric" (Color/valueOf "#AAAAFF")]
     ["preprocessor" (Color/valueOf "#E3A869")]
     ["punctuation" (Color/valueOf "#FD6623")]
     ["keyword" (Color/valueOf "#FD6623")]
     ["storage" (Color/valueOf "#FD6623")]
     ["constant" (Color/valueOf "#FFBBFF")]
     ["support.function" (Color/valueOf "#33CCCC")]
     ["support.variable" (Color/valueOf "#FFBBFF")]
     ["name.function" (Color/valueOf "#33CC33")]
     ["parameter.function" (Color/valueOf "#E3A869")]
     ["variable.language" (Color/valueOf "#E066FF")]
     ["editor.error" (Color/valueOf "#FF6161")]
     ["editor.warning" (Color/valueOf "#FF9A34")]
     ["editor.info" (Color/valueOf "#CCCFD3")]
     ["editor.debug" (Color/valueOf "#3B8CF8")]]))

(defn color-lookup
  ^Paint [color-scheme key]
  (or (some (fn [[pattern paint]]
              (when (= key pattern)
                paint))
            color-scheme)
      (throw (ex-info (str "Missing color scheme key " key)
                      {:key key
                       :keys (mapv first color-scheme)}))))

(defn color-match
  ^Paint [color-scheme scope]
  (or (some (fn [[pattern paint]]
              (when (string/includes? scope pattern)
                paint))
            (take-while (fn [[pattern]]
                          (not (string/starts-with? pattern "editor.")))
                        color-scheme))
      (color-lookup color-scheme "editor.foreground")))

(defn- rect-outline [^Rect r]
  [[(.x r) (+ (.x r) (.w r)) (+ (.x r) (.w r)) (.x r) (.x r)]
   [(.y r) (.y r) (+ (.y r) (.h r)) (+ (.y r) (.h r)) (.y r)]])

(defn- cursor-range-outline [rects]
  (let [^Rect a (first rects)
        ^Rect b (second rects)
        ^Rect y (peek (pop rects))
        ^Rect z (peek rects)]
    (cond
      (nil? b)
      [(rect-outline a)]

      (and (identical? b z) (< (+ (.x b) (.w b)) (.x a)))
      [(rect-outline a)
       (rect-outline b)]

      :else
      [[[(.x b) (.x a) (.x a) (+ (.x a) (.w a)) (+ (.x a) (.w a)) (+ (.x z) (.w z)) (+ (.x z) (.w z)) (.x z) (.x b)]
        [(.y b) (.y b) (.y a) (.y a) (+ (.y y) (.h y)) (+ (.y y) (.h y)) (+ (.y z) (.h z)) (+ (.y z) (.h z)) (.y b)]]])))

(defn- fill-cursor-range! [^GraphicsContext gc type ^Paint fill ^Paint _stroke rects]
  (when (some? fill)
    (.setFill gc fill)
    (case type
      :word (let [^Rect r (data/expand-rect (first rects) 1.0 0.0)]
              (assert (= 1 (count rects)))
              (.fillRoundRect gc (.x r) (.y r) (.w r) (.h r) 5.0 5.0))
      :range (doseq [^Rect r rects]
               (.fillRect gc (.x r) (.y r) (.w r) (.h r)))
      :underline nil
      :squiggle nil)))

(defn- stroke-opaque-polyline! [^GraphicsContext gc xs ys]
  ;; The strokePolyLine method slows down considerably when the shape covers a large
  ;; area of the screen. Drawing individual lines is a lot quicker, but since pixels
  ;; at line ends will be covered twice the stroke must be opaque.
  (loop [^double sx (first xs)
         ^double sy (first ys)
         xs (next xs)
         ys (next ys)]
    (when (and (seq xs) (seq ys))
      (let [^double ex (first xs)
            ^double ey (first ys)]
        (.strokeLine gc sx sy ex ey)
        (recur ex ey (next xs) (next ys))))))

(defn- stroke-cursor-range! [^GraphicsContext gc type ^Paint _fill ^Paint stroke rects]
  (when (some? stroke)
    (.setStroke gc stroke)
    (.setLineWidth gc 1.0)
    (case type
      :word (let [^Rect r (data/expand-rect (first rects) 1.5 0.5)]
              (assert (= 1 (count rects)))
              (.strokeRoundRect gc (.x r) (.y r) (.w r) (.h r) 5.0 5.0))
      :range (doseq [polyline (cursor-range-outline rects)]
               (let [[xs ys] polyline]
                 (stroke-opaque-polyline! gc (double-array xs) (double-array ys))))
      :underline (doseq [^Rect r rects]
                   (let [sx (.x r)
                         ex (+ sx (.w r))
                         y (+ (.y r) (.h r))]
                     (.strokeLine gc sx y ex y)))
      :squiggle (doseq [^Rect r rects]
                  (let [sx (.x r)
                        ex (+ sx (.w r))
                        y (+ (.y r) (.h r))
                        old-line-dashes (.getLineDashes gc)]
                    (doto gc
                      (.setLineDashes (double-array [2.0 3.0]))
                      (.strokeLine sx y ex y)
                      (.setLineDashes old-line-dashes)))))))

(defn- draw-cursor-ranges! [^GraphicsContext gc layout lines cursor-range-draw-infos]
  (let [draw-calls (mapv (fn [^CursorRangeDrawInfo draw-info]
                           [gc
                            (:type draw-info)
                            (:fill draw-info)
                            (:stroke draw-info)
                            (data/cursor-range-rects layout lines (:cursor-range draw-info))])
                         cursor-range-draw-infos)]
    (doseq [args draw-calls]
      (apply fill-cursor-range! args))
    (doseq [args draw-calls]
      (apply stroke-cursor-range! args))))

(defn- leading-whitespace-length
  ^long [^String line]
  (count (take-while #(Character/isWhitespace ^char %) line)))

(defn- find-prior-unindented-row
  ^long [lines ^long row]
  (if (or (zero? row)
          (>= row (count lines)))
    0
    (let [line (lines row)
          leading-whitespace-length (leading-whitespace-length line)
          line-has-text? (some? (get line leading-whitespace-length))]
      (if (and line-has-text? (zero? leading-whitespace-length))
        row
        (recur lines (dec row))))))

(defn- find-prior-indentation-guide-positions [^LayoutInfo layout lines]
  (let [dropped-line-count (.dropped-line-count layout)]
    (loop [row (find-prior-unindented-row lines dropped-line-count)
           guide-positions []]
      (let [line (get lines row)]
        (if (or (nil? line) (<= dropped-line-count row))
          guide-positions
          (let [leading-whitespace-length (leading-whitespace-length line)
                line-has-text? (some? (get line leading-whitespace-length))]
            (if-not line-has-text?
              (recur (inc row) guide-positions)
              (let [guide-x (data/col->x layout leading-whitespace-length line)
                    guide-positions' (conj (into [] (take-while #(< ^double % guide-x)) guide-positions) guide-x)]
                (recur (inc row) guide-positions')))))))))

(defn- fill-text!
  "Draws text onto the canvas. In order to support tab stops, we remap the supplied x
  coordinate into document space, then remap back to canvas coordinates when drawing.
  Returns the canvas x coordinate where the drawn string ends, or nil if drawing
  stopped because we reached the end of the visible canvas region."
  [^GraphicsContext gc ^LayoutInfo layout ^String text start-index end-index x y]
  (let [^Rect canvas-rect (.canvas layout)
        visible-start-x (.x canvas-rect)
        visible-end-x (+ visible-start-x (.w canvas-rect))
        offset-x (+ visible-start-x (.scroll-x layout))]
    (loop [^long i start-index
           x (- ^double x offset-x)]
      (if (= ^long end-index i)
        (+ x offset-x)
        (let [glyph (.charAt text i)
              next-i (inc i)
              next-x (double (data/advance-text layout text i next-i x))
              draw-start-x (+ x offset-x)
              draw-end-x (+ next-x offset-x)
              inside-visible-start? (< visible-start-x draw-end-x)
              inside-visible-end? (< draw-start-x visible-end-x)]
          ;; Currently using FontSmoothingType/GRAY results in poor kerning when
          ;; drawing subsequent characters in a string given to fillText. Here
          ;; glyphs are drawn individually at whole pixels as a workaround.
          (when (and inside-visible-start?
                     inside-visible-end?
                     (not (Character/isWhitespace glyph)))
            (.fillText gc (String/valueOf glyph) draw-start-x y))
          (when inside-visible-end?
            (recur next-i next-x)))))))

(defn- draw-code! [^GraphicsContext gc ^Font font ^LayoutInfo layout color-scheme lines syntax-info indent-type visible-whitespace]
  (let [^Rect canvas-rect (.canvas layout)
        source-line-count (count lines)
        dropped-line-count (.dropped-line-count layout)
        drawn-line-count (.drawn-line-count layout)
        ^double ascent (data/ascent (.glyph layout))
        ^double line-height (data/line-height (.glyph layout))
        visible-whitespace? (= :all visible-whitespace)
        highlight-rogue-whitespace? (not= :none visible-whitespace)
        foreground-color (color-lookup color-scheme "editor.foreground")
        space-color (color-lookup color-scheme "editor.whitespace.space")
        tab-color (color-lookup color-scheme "editor.whitespace.tab")
        rogue-whitespace-color (color-lookup color-scheme "editor.whitespace.rogue")]
    (.setFont gc font)
    (.setTextAlign gc TextAlignment/LEFT)
    (loop [drawn-line-index 0
           source-line-index dropped-line-count]
      (when (and (< drawn-line-index drawn-line-count)
                 (< source-line-index source-line-count))
        (let [^String line (lines source-line-index)
              line-x (+ (.x canvas-rect)
                        (.scroll-x layout))
              line-y (+ ascent
                        (.scroll-y-remainder layout)
                        (* drawn-line-index line-height))]
          (if-some [runs (second (get syntax-info source-line-index))]
            ;; Draw syntax-highlighted runs.
            (loop [run-index 0
                   glyph-offset line-x]
              (when-some [[start scope] (get runs run-index)]
                (.setFill gc (color-match color-scheme scope))
                (let [end (or (first (get runs (inc run-index)))
                              (count line))
                      glyph-offset (fill-text! gc layout line start end glyph-offset line-y)]
                  (when (some? glyph-offset)
                    (recur (inc run-index) (double glyph-offset))))))

            ;; Just draw line as plain text.
            (when-not (string/blank? line)
              (.setFill gc foreground-color)
              (fill-text! gc layout line 0 (count line) line-x line-y)))

          (let [line-length (count line)
                baseline-offset (Math/ceil (/ line-height 4.0))
                visible-start-x (.x canvas-rect)
                visible-end-x (+ visible-start-x (.w canvas-rect))]
            (loop [inside-leading-whitespace? true
                   i 0
                   x 0.0]
              (when (< i line-length)
                (let [character (.charAt line i)
                      next-i (inc i)
                      next-x (double (data/advance-text layout line i next-i x))
                      draw-start-x (+ x line-x)
                      draw-end-x (+ next-x line-x)
                      inside-visible-start? (< visible-start-x draw-end-x)
                      inside-visible-end? (< draw-start-x visible-end-x)]
                  (when (and inside-visible-start? inside-visible-end?)
                    (case character
                      \space (let [sx (+ line-x (Math/floor (* (+ x next-x) 0.5)))
                                   sy (- line-y baseline-offset)]
                               (cond
                                 (and highlight-rogue-whitespace?
                                      inside-leading-whitespace?
                                      (= :tabs indent-type))
                                 (do (.setFill gc rogue-whitespace-color)
                                     (.fillRect gc sx sy 1.0 1.0))

                                 visible-whitespace?
                                 (do (.setFill gc space-color)
                                     (.fillRect gc sx sy 1.0 1.0))))

                      \tab (let [sx (+ line-x x 2.0)
                                 sy (- line-y baseline-offset)]
                             (cond
                               (and highlight-rogue-whitespace?
                                    inside-leading-whitespace?
                                    (not= :tabs indent-type))
                               (do (.setFill gc rogue-whitespace-color)
                                   (.fillRect gc sx sy (- next-x x 4.0) 1.0))

                               visible-whitespace?
                               (do (.setFill gc tab-color)
                                   (.fillRect gc sx sy (- next-x x 4.0) 1.0))))
                      nil))
                  (when inside-visible-end?
                    (recur (and inside-leading-whitespace? (Character/isWhitespace character)) next-i next-x))))))
          (recur (inc drawn-line-index)
                 (inc source-line-index)))))))

(defn- fill-minimap-run!
  [^GraphicsContext gc ^Color color ^LayoutInfo minimap-layout ^String text start-index end-index x y]
  (let [^Rect minimap-rect (.canvas minimap-layout)
        visible-start-x (.x minimap-rect)
        visible-end-x (+ visible-start-x (.w minimap-rect))]
    (.setFill gc (.deriveColor color 0.0 1.0 1.0 0.5))
    (loop [^long i start-index
           x (- ^double x visible-start-x)]
      (if (= ^long end-index i)
        (+ x visible-start-x)
        (let [glyph (.charAt text i)
              next-i (inc i)
              next-x (double (data/advance-text minimap-layout text i next-i x))
              draw-start-x (+ x visible-start-x)
              draw-end-x (+ next-x visible-start-x)
              inside-visible-start? (< visible-start-x draw-end-x)
              inside-visible-end? (< draw-start-x visible-end-x)]
          (when (and inside-visible-start?
                     inside-visible-end?
                     (not= \_ glyph)
                     (not (Character/isWhitespace glyph)))
            (.fillRect gc draw-start-x y (- draw-end-x draw-start-x) 1.0))
          (when inside-visible-end?
            (recur next-i next-x)))))))

(defn- draw-minimap-code! [^GraphicsContext gc ^LayoutInfo minimap-layout color-scheme lines syntax-info]
  (let [^Rect minimap-rect (.canvas minimap-layout)
        source-line-count (count lines)
        dropped-line-count (.dropped-line-count minimap-layout)
        drawn-line-count (.drawn-line-count minimap-layout)
        ^double ascent (data/ascent (.glyph minimap-layout))
        ^double line-height (data/line-height (.glyph minimap-layout))
        foreground-color (color-lookup color-scheme "editor.foreground")]
    (loop [drawn-line-index 0
           source-line-index dropped-line-count]
      (when (and (< drawn-line-index drawn-line-count)
                 (< source-line-index source-line-count))
        (let [^String line (lines source-line-index)
              line-x (.x minimap-rect)
              line-y (+ ascent
                        (.scroll-y-remainder minimap-layout)
                        (* drawn-line-index line-height))]
          (if-some [runs (second (get syntax-info source-line-index))]
            ;; Draw syntax-highlighted runs.
            (loop [run-index 0
                   glyph-offset line-x]
              (when-some [[start scope] (get runs run-index)]
                (let [end (or (first (get runs (inc run-index)))
                              (count line))
                      glyph-offset (fill-minimap-run! gc (color-match color-scheme scope) minimap-layout line start end glyph-offset line-y)]
                  (when (some? glyph-offset)
                    (recur (inc run-index) (double glyph-offset))))))

            ;; Just draw line as plain text.
            (when-not (string/blank? line)
              (fill-minimap-run! gc foreground-color minimap-layout line 0 (count line) line-x line-y)))

          (recur (inc drawn-line-index)
                 (inc source-line-index)))))))

(defn- draw! [^GraphicsContext gc ^Font font gutter-view hovered-element ^LayoutInfo layout ^LayoutInfo minimap-layout color-scheme lines regions syntax-info cursor-range-draw-infos minimap-cursor-range-draw-infos indent-type visible-cursors visible-indentation-guides? visible-minimap? visible-whitespace]
  (let [^Rect canvas-rect (.canvas layout)
        source-line-count (count lines)
        dropped-line-count (.dropped-line-count layout)
        drawn-line-count (.drawn-line-count layout)
        ^double line-height (data/line-height (.glyph layout))
        background-color (color-lookup color-scheme "editor.background")
        scroll-tab-color (color-lookup color-scheme "editor.scroll.tab")
        scroll-tab-hovered-color (color-lookup color-scheme "editor.scroll.tab.hovered")
        hovered-ui-element (:ui-element hovered-element)]
    (.setFill gc background-color)
    (.fillRect gc 0 0 (.. gc getCanvas getWidth) (.. gc getCanvas getHeight))
    (.setFontSmoothingType gc FontSmoothingType/GRAY) ; FontSmoothingType/LCD is very slow.

    ;; Draw cursor ranges.
    (draw-cursor-ranges! gc layout lines cursor-range-draw-infos)

    ;; Draw indentation guides.
    (when visible-indentation-guides?
      (.setStroke gc (color-lookup color-scheme "editor.indentation.guide"))
      (.setLineWidth gc 1.0)
      (loop [drawn-line-index 0
             source-line-index dropped-line-count
             guide-positions (find-prior-indentation-guide-positions layout lines)]
        (when (and (< drawn-line-index drawn-line-count)
                   (< source-line-index source-line-count))
          (let [line (lines source-line-index)
                leading-whitespace-length (count (take-while #(Character/isWhitespace ^char %) line))
                line-has-text? (some? (get line leading-whitespace-length))
                guide-x (data/col->x layout leading-whitespace-length line)
                line-y (data/row->y layout source-line-index)
                guide-positions (if line-has-text? (into [] (take-while #(< ^double % guide-x)) guide-positions) guide-positions)]
            (when (get syntax-info source-line-index)
              (doseq [guide-x guide-positions]
                (.strokeLine gc guide-x line-y guide-x (+ line-y (dec line-height)))))
            (recur (inc drawn-line-index)
                   (inc source-line-index)
                   (if line-has-text? (conj guide-positions guide-x) guide-positions))))))

    ;; Draw syntax-highlighted code.
    (draw-code! gc font layout color-scheme lines syntax-info indent-type visible-whitespace)

    ;; Draw minimap.
    (when visible-minimap?
      (let [^Rect r (.canvas minimap-layout)
            ^double document-line-height (data/line-height (.glyph layout))
            ^double minimap-line-height (data/line-height (.glyph minimap-layout))
            minimap-ratio (/ minimap-line-height document-line-height)
            viewed-start-y (+ (* minimap-ratio (- (.scroll-y layout))) (.scroll-y minimap-layout))
            viewed-height (Math/ceil (* minimap-ratio (.h canvas-rect)))]
        (.setFill gc background-color)
        (.fillRect gc (.x r) (.y r) (.w r) (.h r))

        ;; Draw the viewed range if the mouse hovers the minimap.
        (case hovered-ui-element
          (:minimap :minimap-viewed-range)
          (let [color (color-lookup color-scheme  "editor.minimap.viewed.range")]
            (.setFill gc color)
            (.fillRect gc (.x r) viewed-start-y (.w r) viewed-height))

          nil)

        (draw-cursor-ranges! gc minimap-layout lines minimap-cursor-range-draw-infos)
        (draw-minimap-code! gc minimap-layout color-scheme lines syntax-info)

        ;; Draw minimap shadow if it covers part of the document.
        (when-some [^Rect scroll-tab-rect (some-> (.scroll-tab-x layout))]
          (when (not= (+ (.x canvas-rect) (.w canvas-rect)) (+ (.x scroll-tab-rect) (.w scroll-tab-rect)))
            (.setFill gc (color-lookup color-scheme "editor.minimap.shadow"))
            (.fillRect gc (- (.x r) 8.0) (.y r) 8.0 (.h r))))))

    ;; Draw horizontal scroll bar.
    (when-some [^Rect r (some-> (.scroll-tab-x layout) (data/expand-rect -3.0 -3.0))]
      (.setFill gc (if (= :scroll-tab-x hovered-ui-element) scroll-tab-hovered-color scroll-tab-color))
      (.fillRoundRect gc (.x r) (.y r) (.w r) (.h r) (.h r) (.h r)))

    ;; Draw vertical scroll bar.
    (when-some [^Rect r (some-> (.scroll-tab-y layout) (data/expand-rect -3.0 -3.0))]
      (let [color (case hovered-ui-element
                    (:scroll-bar-y-down :scroll-bar-y-up :scroll-tab-y) scroll-tab-hovered-color
                    scroll-tab-color)]
        (.setFill gc color)
        (.fillRoundRect gc (.x r) (.y r) (.w r) (.h r) (.w r) (.w r))

        ;; Draw dots around the scroll tab when hovering over the continuous scroll areas of the vertical scroll bar.
        (let [size 4.0
              dist 7.0
              offset 9.0
              half-size (* 0.5 size)
              half-tab-width (* 0.5 (.w r))]
          (case hovered-ui-element
            :scroll-bar-y-down
            (let [cx (- (+ (.x r) half-tab-width) half-size)
                  cy (- (+ (.y r) (.h r) offset) half-tab-width half-size)]
              (doseq [i (range 3)]
                (.fillOval gc cx (+ cy (* ^double i dist)) size size)))

            :scroll-bar-y-up
            (let [cx (- (+ (.x r) half-tab-width) half-size)
                  cy (- (+ (.y r) half-tab-width) half-size offset)]
              (doseq [i (range 3)]
                (.fillOval gc cx (- cy (* ^double i dist)) size size)))

            nil))))

    ;; Draw gutter.
    (let [^Rect gutter-rect (data/->Rect 0.0 (.y canvas-rect) (.x canvas-rect) (.h canvas-rect))]
      (when (< 0.0 (.w gutter-rect))
        (draw-gutter! gutter-view gc gutter-rect layout font color-scheme lines regions visible-cursors)))))

;; -----------------------------------------------------------------------------

(g/defnk produce-indent-type [indent-type]
  ;; Defaults to :tabs when not connected to a resource node.
  (or indent-type :tabs))

(g/defnk produce-indent-string [indent-type]
  (data/indent-type->indent-string indent-type))

(g/defnk produce-tab-spaces [indent-type]
  (data/indent-type->tab-spaces indent-type))

(g/defnk produce-indent-level-pattern [tab-spaces]
  (data/indent-level-pattern tab-spaces))

(g/defnk produce-font [font-name font-size]
  (Font. font-name font-size))

(g/defnk produce-glyph-metrics [font line-height-factor]
  (make-glyph-metrics font line-height-factor))

(g/defnk produce-gutter-metrics [gutter-view lines regions glyph-metrics]
  (gutter-metrics gutter-view lines regions glyph-metrics))

(g/defnk produce-layout [canvas-width canvas-height document-width scroll-x scroll-y lines gutter-metrics glyph-metrics tab-spaces visible-minimap?]
  (let [[gutter-width gutter-margin] gutter-metrics]
    (data/layout-info canvas-width canvas-height document-width scroll-x scroll-y lines gutter-width gutter-margin glyph-metrics tab-spaces visible-minimap?)))

(defn- invalidated-row
  "Find the first invalidated row by comparing ever-growing histories of all
  invalidated rows. Seen means the history at the point the view was last drawn.
  By looking at the subset of added-since-seen or removed-since-seen, we can
  find the first invalidated row since the previous repaint."
  [seen-invalidated-rows invalidated-rows]
  (let [seen-invalidated-rows-count (count seen-invalidated-rows)
        invalidated-rows-count (count invalidated-rows)]
    (cond
      ;; Redo scenario or regular use.
      (< seen-invalidated-rows-count invalidated-rows-count)
      (reduce min (subvec invalidated-rows seen-invalidated-rows-count))

      ;; Undo scenario.
      (> seen-invalidated-rows-count invalidated-rows-count)
      (reduce min (subvec seen-invalidated-rows invalidated-rows-count)))))

(g/defnk produce-matching-braces [lines cursor-ranges focus-state]
  (when (= :input-focused focus-state)
    (into []
          (comp (filter data/cursor-range-empty?)
                (map data/CursorRange->Cursor)
                (map (partial data/adjust-cursor lines))
                (keep (partial data/find-matching-braces lines)))
          cursor-ranges)))

(g/defnk produce-tab-trigger-scope-regions [regions]
  (filterv #(= :tab-trigger-scope (:type %)) regions))

(g/defnk produce-tab-trigger-regions-by-scope-id [regions]
  (->> regions
       (eduction
         (filter #(let [type (:type %)]
                    (or (= :tab-trigger-word type)
                        (= :tab-trigger-exit type)))))
       (group-by :scope-id)))

(g/defnk produce-visible-cursors [visible-cursor-ranges focus-state]
  (when (= :input-focused focus-state)
    (mapv data/CursorRange->Cursor visible-cursor-ranges)))

(g/defnk produce-visible-cursor-ranges [lines cursor-ranges layout]
  (data/visible-cursor-ranges lines layout cursor-ranges))

(g/defnk produce-visible-regions [lines regions layout]
  (data/visible-cursor-ranges lines layout regions))

(g/defnk produce-visible-matching-braces [lines matching-braces layout]
  (data/visible-cursor-ranges lines layout (into [] (mapcat identity) matching-braces)))

(defn- make-execution-marker-draw-info
  [current-color frame-color {:keys [location-type] :as execution-marker}]
  (case location-type
    :current-line
    (cursor-range-draw-info :word nil current-color execution-marker)

    :current-frame
    (cursor-range-draw-info :word nil frame-color execution-marker)))

(g/defnk produce-cursor-range-draw-infos [color-scheme lines cursor-ranges focus-state layout visible-cursors visible-cursor-ranges visible-regions visible-matching-braces highlighted-find-term find-case-sensitive? find-whole-word? execution-markers]
  (let [background-color (color-lookup color-scheme "editor.background")
        ^Color selection-background-color (color-lookup color-scheme "editor.selection.background")
        selection-background-color (if (not= :not-focused focus-state) selection-background-color (color-lookup color-scheme "editor.selection.background.inactive"))
        selection-occurrence-outline-color (color-lookup color-scheme "editor.selection.occurrence.outline")
        tab-trigger-word-outline-color (color-lookup color-scheme "editor.tab.trigger.word.outline")
        find-term-occurrence-color (color-lookup color-scheme "editor.find.term.occurrence")
        execution-marker-current-row-color (color-lookup color-scheme "editor.execution-marker.current")
        execution-marker-frame-row-color (color-lookup color-scheme "editor.execution-marker.frame")
        matching-brace-color (color-lookup color-scheme "editor.matching.brace")
        foreground-color (color-lookup color-scheme "editor.foreground")
        error-color (color-lookup color-scheme "editor.error")
        warning-color (color-lookup color-scheme "editor.warning")
        info-color (color-lookup color-scheme "editor.info")
        debug-color (color-lookup color-scheme "editor.debug")
        visible-regions-by-type (group-by :type visible-regions)
        active-tab-trigger-scope-ids (into #{}
                                           (keep (fn [tab-trigger-scope-region]
                                                   (when (some #(data/cursor-range-contains? tab-trigger-scope-region (data/CursorRange->Cursor %))
                                                               cursor-ranges)
                                                     (:id tab-trigger-scope-region))))
                                           (visible-regions-by-type :tab-trigger-scope))]
    (vec
      (concat
        (map (partial cursor-range-draw-info :range selection-background-color background-color)
             visible-cursor-ranges)
        (map (partial cursor-range-draw-info :underline nil matching-brace-color)
             visible-matching-braces)
        (map (partial cursor-range-draw-info :underline nil foreground-color)
             (visible-regions-by-type :resource-reference))
        (map (fn [{:keys [severity] :as region}]
               (cursor-range-draw-info :squiggle
                                       nil
                                       (case severity
                                         :error error-color
                                         :warning warning-color
                                         :information info-color
                                         :hint debug-color)
                                       region))
             (visible-regions-by-type :diagnostic))
        (map (partial make-execution-marker-draw-info execution-marker-current-row-color execution-marker-frame-row-color)
             execution-markers)
        (cond
          (seq active-tab-trigger-scope-ids)
          (keep (fn [tab-trigger-region]
                  (when (and (contains? active-tab-trigger-scope-ids (:scope-id tab-trigger-region))
                             (not-any? #(data/cursor-range-contains? tab-trigger-region %)
                                       visible-cursors))
                    (cursor-range-draw-info :range nil tab-trigger-word-outline-color tab-trigger-region)))
                (concat (visible-regions-by-type :tab-trigger-word)
                        (visible-regions-by-type :tab-trigger-exit)))

          (not (empty? highlighted-find-term))
          (map (partial cursor-range-draw-info :range nil find-term-occurrence-color)
               (data/visible-occurrences lines layout find-case-sensitive? find-whole-word? (split-lines highlighted-find-term)))

          :else
          (map (partial cursor-range-draw-info :word nil selection-occurrence-outline-color)
               (data/visible-occurrences-of-selected-word lines cursor-ranges layout visible-cursor-ranges)))))))

(g/defnk produce-minimap-glyph-metrics [font-name]
  (assoc (make-glyph-metrics (Font. font-name 2.0) 1.0) :line-height 2.0))

(g/defnk produce-minimap-layout [layout lines minimap-glyph-metrics tab-spaces]
  (data/minimap-layout-info layout (count lines) minimap-glyph-metrics tab-spaces))

(g/defnk produce-minimap-cursor-range-draw-infos [color-scheme lines cursor-ranges minimap-layout highlighted-find-term find-case-sensitive? find-whole-word? execution-markers]
  (let [execution-marker-current-row-color (color-lookup color-scheme "editor.execution-marker.current")
        execution-marker-frame-row-color (color-lookup color-scheme "editor.execution-marker.frame")]
    (vec
      (concat
        (map (partial make-execution-marker-draw-info execution-marker-current-row-color execution-marker-frame-row-color)
             execution-markers)
        (cond
          (not (empty? highlighted-find-term))
          (map (partial cursor-range-draw-info :range (color-lookup color-scheme "editor.find.term.occurrence") nil)
               (data/visible-occurrences lines minimap-layout find-case-sensitive? find-whole-word? (split-lines highlighted-find-term)))

          :else
          (map (partial cursor-range-draw-info :range (color-lookup color-scheme "editor.selection.occurrence.outline") nil)
               (data/visible-occurrences-of-selected-word lines cursor-ranges minimap-layout nil)))))))

(g/defnk produce-execution-markers [lines debugger-execution-locations node-id+type+resource]
  (when-some [path (some-> node-id+type+resource (get 2) resource/proj-path)]
    (into []
          (comp (filter #(= path (:file %)))
                (map (fn [{:keys [^long line type]}]
                       (data/execution-marker lines (dec line) type))))
          debugger-execution-locations)))

(g/defnk produce-canvas-repaint-info [canvas color-scheme cursor-range-draw-infos execution-markers font grammar gutter-view hovered-element indent-type invalidated-rows layout lines minimap-cursor-range-draw-infos minimap-layout regions repaint-trigger visible-cursors visible-indentation-guides? visible-minimap? visible-whitespace :as canvas-repaint-info]
  canvas-repaint-info)

(defn- repaint-canvas! [{:keys [^Canvas canvas execution-markers font gutter-view hovered-element layout minimap-layout color-scheme lines regions cursor-range-draw-infos minimap-cursor-range-draw-infos indent-type visible-cursors visible-indentation-guides? visible-minimap? visible-whitespace] :as _canvas-repaint-info} syntax-info]
  (let [regions (into [] cat [regions execution-markers])]
    (draw! (.getGraphicsContext2D canvas) font gutter-view hovered-element layout minimap-layout color-scheme lines regions syntax-info cursor-range-draw-infos minimap-cursor-range-draw-infos indent-type visible-cursors visible-indentation-guides? visible-minimap? visible-whitespace))
  nil)

(g/defnk produce-cursor-repaint-info [canvas color-scheme cursor-opacity layout lines repaint-trigger visible-cursors :as cursor-repaint-info]
  cursor-repaint-info)

(defn- make-cursor-rectangle
  ^Rectangle [^Paint fill opacity ^Rect cursor-rect]
  (doto (Rectangle. (.x cursor-rect) (.y cursor-rect) (.w cursor-rect) (.h cursor-rect))
    (.setMouseTransparent true)
    (.setFill fill)
    (.setOpacity opacity)))

(defn- repaint-cursors! [{:keys [^Canvas canvas ^LayoutInfo layout color-scheme lines visible-cursors cursor-opacity] :as _cursor-repaint-info}]
  ;; To avoid having to redraw everything while the cursor blink animation
  ;; plays, the cursors are children of the Pane that also hosts the Canvas.
  (let [^Pane canvas-pane (.getParent canvas)
        ^Rect canvas-rect (.canvas layout)
        ^Rect minimap-rect (.minimap layout)
        gutter-end (dec (.x canvas-rect))
        canvas-end (.x minimap-rect)
        children (.getChildren canvas-pane)
        cursor-color (color-lookup color-scheme "editor.cursor")
        cursor-rectangles (into []
                                (comp (map (partial data/cursor-rect layout lines))
                                      (remove (fn [^Rect cursor-rect] (< (.x cursor-rect) gutter-end)))
                                      (remove (fn [^Rect cursor-rect] (> (.x cursor-rect) canvas-end)))
                                      (map (partial make-cursor-rectangle cursor-color cursor-opacity)))
                                visible-cursors)]
    (assert (identical? canvas (first children)))
    (.remove children 1 (count children))
    (.addAll children ^Collection cursor-rectangles)
    nil))

(g/defnk produce-completion-context
  "Returns a map of completion-related information

  The map includes following keys:
    :context          required, a string indicating a context in which the
                      completions should be requested, e.g. for a string
                      \"socket.d\" before cursor the context would be \"socket\"
    :query            required, a string used for filtering completions, e.g.
                      for a string \"socket.d\" before cursor the query would
                      be \"d\"
    :cursor-ranges    required, replacement ranges that should be used when
                      accepting the suggestion
    :trigger          optional trigger character string, e.g. \".\""
  [lines cursor-ranges]
  {:pre [(pos? (count cursor-ranges))]}
  (let [results (mapv (fn [^CursorRange cursor-range]
                        (let [suggestion-cursor (data/adjust-cursor lines (data/cursor-range-start cursor-range))
                              line (subs (lines (.-row suggestion-cursor)) 0 (.-col suggestion-cursor))
                              prefix (or (re-find #"[a-zA-Z_][a-zA-Z_0-9.]*$" line) "")
                              affected-cursor (if (pos? (data/compare-cursor-position
                                                          (.-from cursor-range)
                                                          (.-to cursor-range)))
                                                :to
                                                :from)
                              last-dot (string/last-index-of prefix ".")
                              context (if last-dot (subs prefix 0 last-dot) "")
                              query (if last-dot (subs prefix (inc ^long last-dot)) prefix)
                              replacement-range (update cursor-range affected-cursor update :col - (count query))]
                          [replacement-range context query (if last-dot "." nil)]))
                      cursor-ranges)]
    (let [[_ context query trigger] (first results)]
      (cond-> {:context context
               :query query
               :cursor-ranges (mapv first results)}
              trigger
              (assoc :trigger trigger)))))

(g/defnk produce-visible-completion-ranges [lines layout completion-context]
  (data/visible-cursor-ranges lines layout (:cursor-ranges completion-context)))

(defn- find-tab-trigger-scope-region-at-cursor
  ^CursorRange [tab-trigger-scope-regions ^Cursor cursor]
  (some (fn [scope-region]
          (when (data/cursor-range-contains? scope-region cursor)
            scope-region))
        tab-trigger-scope-regions))

(defn- find-tab-trigger-region-at-cursor
  ^CursorRange [tab-trigger-scope-regions tab-trigger-regions-by-scope-id ^Cursor cursor]
  (when-some [scope-region (find-tab-trigger-scope-region-at-cursor tab-trigger-scope-regions cursor)]
    (some (fn [region]
            (when (data/cursor-range-contains? region cursor)
              region))
          (get tab-trigger-regions-by-scope-id (:id scope-region)))))

(g/defnk produce-suggested-completions [tab-trigger-scope-regions
                                        tab-trigger-regions-by-scope-id
                                        cursor-ranges
                                        completions
                                        completion-context]
  (let [choices-colls (into []
                            (comp
                              (map data/CursorRange->Cursor)
                              (keep (partial find-tab-trigger-region-at-cursor
                                             tab-trigger-scope-regions
                                             tab-trigger-regions-by-scope-id))
                              (keep :choices))
                            cursor-ranges)
        unfiltered-completions (if (pos? (count choices-colls))
                                 (into [] (comp cat (map code-completion/make)) choices-colls)
                                 (get completions (:context completion-context)))]
    (vec (popup/fuzzy-option-filter-fn
           :name
           :display-string
           (:query completion-context)
           unfiltered-completions))))

(g/defnode CodeEditorView
  (inherits view/WorkbenchView)

  (property repaint-trigger g/Num (default 0) (dynamic visible (g/constantly false)))
  (property undo-grouping-info UndoGroupingInfo (dynamic visible (g/constantly false)))
  (property canvas Canvas (dynamic visible (g/constantly false)))
  (property canvas-width g/Num (default 0.0) (dynamic visible (g/constantly false))
            (set (fn [evaluation-context self _old-value _new-value]
                   (let [layout (g/node-value self :layout evaluation-context)
                         scroll-x (g/node-value self :scroll-x evaluation-context)
                         new-scroll-x (data/limit-scroll-x layout scroll-x)]
                     (when (not= scroll-x new-scroll-x)
                       (g/set-property self :scroll-x new-scroll-x))))))
  (property canvas-height g/Num (default 0.0) (dynamic visible (g/constantly false))
            (set (fn [evaluation-context self old-value new-value]
                   ;; NOTE: old-value will be nil when the setter is invoked for the default.
                   ;; However, our calls to g/node-value will see default values for all
                   ;; properties even though their setter fns have not yet been called.
                   (let [^double old-value (or old-value 0.0)
                         ^double new-value new-value
                         scroll-y (g/node-value self :scroll-y evaluation-context)
                         layout (g/node-value self :layout evaluation-context)
                         line-count (count (g/node-value self :lines evaluation-context))
                         resize-reference (g/node-value self :resize-reference evaluation-context)
                         new-scroll-y (data/limit-scroll-y layout line-count (case resize-reference
                                                                               :bottom (- ^double scroll-y (- old-value new-value))
                                                                               :top scroll-y))]
                     (when (not= scroll-y new-scroll-y)
                       (g/set-property self :scroll-y new-scroll-y))))))
  (property diagnostics r/Regions (default []) (dynamic visible (g/constantly false)))
  (property document-width g/Num (default 0.0) (dynamic visible (g/constantly false)))
  (property color-scheme ColorScheme (dynamic visible (g/constantly false)))
  (property elapsed-time-at-last-action g/Num (default 0.0) (dynamic visible (g/constantly false)))
  (property grammar g/Any (dynamic visible (g/constantly false)))
  (property gutter-view GutterView (dynamic visible (g/constantly false)))
  (property cursor-opacity g/Num (default 1.0) (dynamic visible (g/constantly false)))
  (property resize-reference ResizeReference (default :top) (dynamic visible (g/constantly false)))
  (property scroll-x g/Num (default 0.0) (dynamic visible (g/constantly false)))
  (property scroll-y g/Num (default 0.0) (dynamic visible (g/constantly false)))
  (property completion-state CompletionState (default {:enabled false}) (dynamic visible (g/constantly false)))
  (property gesture-start GestureInfo (dynamic visible (g/constantly false)))
  (property highlighted-find-term g/Str (default "") (dynamic visible (g/constantly false)))
  (property hovered-element HoveredElement (dynamic visible (g/constantly false)))
  (property edited-breakpoint r/Region (dynamic visible (g/constantly false)))
  (property find-case-sensitive? g/Bool (dynamic visible (g/constantly false)))
  (property find-whole-word? g/Bool (dynamic visible (g/constantly false)))
  (property focus-state FocusState (default :not-focused) (dynamic visible (g/constantly false)))

  (property font-name g/Str (default "Dejavu Sans Mono"))
  (property font-size g/Num (default (g/constantly default-font-size)))
  (property line-height-factor g/Num (default 1.0))
  (property visible-indentation-guides? g/Bool (default false))
  (property visible-minimap? g/Bool (default false))
  (property visible-whitespace VisibleWhitespace (default :none))

  (input completions g/Any)
  (input cursor-ranges r/CursorRanges)
  (input indent-type r/IndentType)
  (input invalidated-rows r/InvalidatedRows)
  (input lines r/Lines :substitute [""])
  (input regions r/Regions)
  (input debugger-execution-locations g/Any)

  (output completion-context g/Any :cached produce-completion-context)
  (output visible-completion-ranges g/Any :cached produce-visible-completion-ranges)
  (output suggested-completions g/Any :cached produce-suggested-completions)
  ;; We cache the lines in the view instead of the resource node, since the
  ;; resource node will read directly from disk unless edits have been made.
  (output lines r/Lines :cached (gu/passthrough lines))
  (output regions r/Regions :cached (g/fnk [regions diagnostics]
                                      (vec (sort (into regions diagnostics)))))
  (output indent-type r/IndentType produce-indent-type)
  (output indent-string g/Str produce-indent-string)
  (output tab-spaces g/Num produce-tab-spaces)
  (output indent-level-pattern Pattern :cached produce-indent-level-pattern)
  (output font Font :cached produce-font)
  (output glyph-metrics data/GlyphMetrics :cached produce-glyph-metrics)
  (output gutter-metrics GutterMetrics :cached produce-gutter-metrics)
  (output layout LayoutInfo :cached produce-layout)
  (output matching-braces MatchingBraces :cached produce-matching-braces)
  (output tab-trigger-scope-regions r/Regions :cached produce-tab-trigger-scope-regions)
  (output tab-trigger-regions-by-scope-id r/RegionGrouping :cached produce-tab-trigger-regions-by-scope-id)
  (output visible-cursors r/Cursors :cached produce-visible-cursors)
  (output visible-cursor-ranges r/CursorRanges :cached produce-visible-cursor-ranges)
  (output visible-regions r/Regions :cached produce-visible-regions)
  (output visible-matching-braces r/CursorRanges :cached produce-visible-matching-braces)
  (output cursor-range-draw-infos CursorRangeDrawInfos :cached produce-cursor-range-draw-infos)
  (output minimap-glyph-metrics data/GlyphMetrics :cached produce-minimap-glyph-metrics)
  (output minimap-layout LayoutInfo :cached produce-minimap-layout)
  (output minimap-cursor-range-draw-infos CursorRangeDrawInfos :cached produce-minimap-cursor-range-draw-infos)
  (output execution-markers r/Regions :cached produce-execution-markers)
  (output canvas-repaint-info g/Any :cached produce-canvas-repaint-info)
  (output cursor-repaint-info g/Any :cached produce-cursor-repaint-info))

(defn- mouse-button [^MouseEvent event]
  (condp = (.getButton event)
    MouseButton/NONE nil
    MouseButton/PRIMARY :primary
    MouseButton/SECONDARY :secondary
    MouseButton/MIDDLE :middle
    MouseButton/BACK :back
    MouseButton/FORWARD :forward))

(defn- operation-sequence-tx-data [view-node undo-grouping]
  (if (nil? undo-grouping)
    []
    (let [[prev-undo-grouping prev-opseq] (g/node-value view-node :undo-grouping-info)]
      (assert (contains? undo-groupings undo-grouping))
      (cond
        (= undo-grouping prev-undo-grouping)
        [(g/operation-sequence prev-opseq)]

        (and (contains? #{:navigation :selection} undo-grouping)
             (contains? #{:navigation :selection} prev-undo-grouping))
        [(g/operation-sequence prev-opseq)
         (g/set-property view-node :undo-grouping-info [undo-grouping prev-opseq])]

        :else
        (let [opseq (gensym)]
          [(g/operation-sequence opseq)
           (g/set-property view-node :undo-grouping-info [undo-grouping opseq])])))))

(defn- prelude-tx-data [view-node undo-grouping values-by-prop-kw]
  ;; Along with undo grouping info, we also keep track of when an action was
  ;; last performed in the document. We use this to stop the cursor from
  ;; blinking while typing or navigating.
  (into (operation-sequence-tx-data view-node undo-grouping)
        (when (or (contains? values-by-prop-kw :cursor-ranges)
                  (contains? values-by-prop-kw :lines))
          (g/set-property view-node :elapsed-time-at-last-action (or (g/user-data view-node :elapsed-time) 0.0)))))

;; -----------------------------------------------------------------------------

;; The functions that perform actions in the core.data module return maps of
;; properties that were modified by the operation. Some of these properties need
;; to be stored at various locations in order to be undoable, some are transient,
;; and so on. These two functions should be used to read and write these managed
;; properties at all times. Basically, get-property needs to be able to get any
;; property that is supplied to set-properties! in values-by-prop-kw.

(defn get-property
  "Gets the value of a property that is managed by the functions in the code.data module."
  ([view-node prop-kw]
   (g/with-auto-evaluation-context evaluation-context
     (get-property view-node prop-kw evaluation-context)))
  ([view-node prop-kw evaluation-context]
   (case prop-kw
     :invalidated-row
     (invalidated-row (:invalidated-rows (g/user-data view-node :canvas-repaint-info))
                      (g/node-value view-node :invalidated-rows evaluation-context))

     (g/node-value view-node prop-kw evaluation-context))))

(defn set-properties!
  "Sets values of properties that are managed by the functions in the code.data module.
  Returns true if any property changed, false otherwise."
  [view-node undo-grouping values-by-prop-kw]
  (if (empty? values-by-prop-kw)
    false
    (let [resource-node (g/node-value view-node :resource-node)]
      (g/transact
        (into (prelude-tx-data view-node undo-grouping values-by-prop-kw)
              (mapcat (fn [[prop-kw value]]
                        (case prop-kw
                          :cursor-ranges
                          (g/set-property resource-node prop-kw value)

                          :regions
                          (let [{diagnostics true regions false} (group-by #(= :diagnostic (:type %)) value)]
                            (concat
                              (g/set-property view-node :diagnostics (or diagnostics []))
                              (g/set-property resource-node prop-kw (or regions []))))

                          ;; Several actions might have invalidated rows since
                          ;; we last produced syntax-info. We keep an ever-
                          ;; growing history of invalidated-rows. Then when
                          ;; producing syntax-info we find the first invalidated
                          ;; row by comparing the history of invalidated rows to
                          ;; what it was at the time of the last call. See the
                          ;; invalidated-row function for details.
                          :invalidated-row
                          (g/update-property resource-node :invalidated-rows conj value)

                          ;; The :indent-type output in the resource node is
                          ;; cached, but reads from disk unless a value exists
                          ;; for the :modified-indent-type property.
                          :indent-type
                          (g/set-property resource-node :modified-indent-type value)

                          ;; The :lines output in the resource node is uncached.
                          ;; It reads from disk unless a value exists for the
                          ;; :modified-lines property. This means only modified
                          ;; or currently open files are kept in memory.
                          :lines
                          (g/set-property resource-node :modified-lines value)

                          ;; All other properties are set on the view node.
                          (g/set-property view-node prop-kw value))))
              values-by-prop-kw))
      true)))

;; -----------------------------------------------------------------------------
;; Code completion
;; -----------------------------------------------------------------------------

(defn- cursor-bottom
  ^Point2D [^LayoutInfo layout lines ^Cursor adjusted-cursor]
  (let [^Rect r (data/cursor-rect layout lines adjusted-cursor)]
    (Point2D. (.x r) (+ (.y r) (.h r)))))

(defn- pref-suggestions-popup-position
  ^Point2D [^Canvas canvas width height ^Point2D cursor-bottom]
  (Utils/pointRelativeTo canvas width height HPos/CENTER VPos/CENTER (.getX cursor-bottom) (.getY cursor-bottom) true))

(defn- show-suggestions!
  ([view-node toggle-doc-if-open]
   (g/with-auto-evaluation-context evaluation-context
     (show-suggestions! view-node toggle-doc-if-open evaluation-context)))
  ([view-node toggle-doc-if-open evaluation-context]
   (let [new-completions (get-property view-node :suggested-completions evaluation-context)
         lines (get-property view-node :lines evaluation-context)
         cursor-ranges (get-property view-node :cursor-ranges evaluation-context)
         frame-cursor-props (-> {:lines lines
                                 :cursor-ranges cursor-ranges}
                                (data/frame-cursor
                                  (get-property view-node :layout evaluation-context))
                                (dissoc :lines :cursor-ranges))]
     (when (pos? (count frame-cursor-props))
       (set-properties! view-node nil frame-cursor-props))
     (g/update-property! view-node :completion-state
                         (fn [{:keys [showing completions] :as completion-state}]
                           (-> completion-state
                               (assoc :showing true
                                      :completions new-completions)
                               (cond-> (not= new-completions completions)
                                       (assoc :selected-index nil)

                                       (and showing toggle-doc-if-open)
                                       (update :show-doc not))))))))

(defn- implies-completions?
  ([view-node]
   (g/with-auto-evaluation-context evaluation-context
     (implies-completions? view-node evaluation-context)))
  ([view-node evaluation-context]
   (let [{:keys [query trigger]} (get-property view-node :completion-context evaluation-context)]
     (or (some? trigger) (pos? (count query))))))

(defn- hide-suggestions! [view-node]
  (g/update-property! view-node :completion-state assoc :showing false :selected-index nil))

(defn- suggestions-shown? [view-node]
  (let [{:keys [enabled showing]} (get-property view-node :completion-state)]
    (and enabled showing)))

(defn- suggestions-visible? [view-node]
  (let [{:keys [enabled showing completions]} (get-property view-node :completion-state)]
    (and enabled showing (pos? (count completions)))))

(defn- selected-suggestion [view-node]
  (let [{:keys [enabled showing completions selected-index]} (get-property view-node :completion-state)]
    (when (and enabled showing (pos? (count completions)))
      (get completions (or selected-index 0)))))

(defn- in-tab-trigger? [view-node]
  (let [tab-trigger-scope-regions (get-property view-node :tab-trigger-scope-regions)]
    (if (empty? tab-trigger-scope-regions)
      false
      (let [cursor-ranges (get-property view-node :cursor-ranges)]
        (some? (some #(find-tab-trigger-scope-region-at-cursor tab-trigger-scope-regions
                                                               (data/CursorRange->Cursor %))
                     cursor-ranges))))))

(defn- tab-trigger-related-region? [^CursorRange region]
  (case (:type region)
    (:tab-trigger-scope :tab-trigger-word :tab-trigger-exit) true
    false))

(defn- exit-tab-trigger! [view-node]
  (hide-suggestions! view-node)
  (when (in-tab-trigger? view-node)
    (set-properties! view-node :navigation
                     {:regions (into []
                                     (remove tab-trigger-related-region?)
                                     (get-property view-node :regions))})))

(defn- find-closest-tab-trigger-regions
  [search-direction tab-trigger-scope-regions tab-trigger-regions-by-scope-id ^CursorRange cursor-range]
  ;; NOTE: Cursor range collections are assumed to be in ascending order.
  (let [cursor-range->cursor (case search-direction :prev data/cursor-range-start :next data/cursor-range-end)
        from-cursor (cursor-range->cursor cursor-range)]
    (if-some [scope-region (find-tab-trigger-scope-region-at-cursor tab-trigger-scope-regions from-cursor)]
      ;; The cursor range is inside a tab trigger scope region.
      ;; Try to find the next word region inside the scope region.
      (let [scope-id (:id scope-region)
            selected-tab-region (find-tab-trigger-region-at-cursor
                                  tab-trigger-scope-regions
                                  tab-trigger-regions-by-scope-id
                                  from-cursor)
            selected-index (if selected-tab-region
                             (case (:type selected-tab-region)
                               :tab-trigger-word (:index selected-tab-region)
                               :tab-trigger-exit ##Inf)
                             (case search-direction
                               :next ##-Inf
                               :prev ##Inf))
            tab-trigger-regions (get tab-trigger-regions-by-scope-id scope-id)
            index->tab-triggers (->> tab-trigger-regions
                                     (eduction
                                       (filter #(= :tab-trigger-word (:type %))))
                                     (util/group-into (sorted-map) [] :index))]
        (or
          ;; find next region by index
          (when-let [[_ regions] (first (case search-direction
                                          :next (subseq index->tab-triggers > selected-index)
                                          :prev (rsubseq index->tab-triggers < selected-index)))]
            {:regions (mapv data/sanitize-cursor-range regions)})
          ;; no further regions found: exit if moving forward
          (when (= :next search-direction)
            {:regions (or
                        ;; if there are explicit exit regions, use those
                        (not-empty
                          (into []
                                (keep
                                  #(when (= :tab-trigger-exit (:type %))
                                     (data/sanitize-cursor-range %)))
                                tab-trigger-regions))
                        ;; otherwise, exit to the end of the scope
                        [(-> scope-region
                             data/cursor-range-end-range
                             data/sanitize-cursor-range)])
             :exit (into [scope-region] tab-trigger-regions)})

          ;; fallback: return the same range
          {:regions [cursor-range]}))

      ;; The cursor range is outside any tab trigger scope ranges. Return unchanged.
      {:regions [cursor-range]})))

(defn- select-closest-tab-trigger-region! [search-direction view-node]
  (when-some [tab-trigger-scope-regions (not-empty (get-property view-node :tab-trigger-scope-regions))]
    (let [tab-trigger-regions-by-scope-id (get-property view-node :tab-trigger-regions-by-scope-id)
          find-closest-tab-trigger-regions (partial find-closest-tab-trigger-regions
                                                    search-direction
                                                    tab-trigger-scope-regions
                                                    tab-trigger-regions-by-scope-id)
          cursor-ranges (get-property view-node :cursor-ranges)
          new-cursor-ranges+exits (mapv find-closest-tab-trigger-regions cursor-ranges)
          removed-regions (into #{} (mapcat :exit) new-cursor-ranges+exits)
          new-cursor-ranges (vec (sort (into #{} (mapcat :regions) new-cursor-ranges+exits)))
          regions (get-property view-node :regions)
          new-regions (into [] (remove removed-regions) regions)]
      (set-properties! view-node :selection
                       (cond-> {:cursor-ranges new-cursor-ranges}

                               (not= (count regions) (count new-regions))
                               (assoc :regions new-regions))))))

(def ^:private prev-tab-trigger! #(select-closest-tab-trigger-region! :prev %))
(def ^:private next-tab-trigger! #(select-closest-tab-trigger-region! :next %))

(defn- accept-suggestion!
  ([view-node]
   (when-let [completion (selected-suggestion view-node)]
     (accept-suggestion! view-node (code-completion/insertion completion))))
  ([view-node insertion]
   (let [indent-level-pattern (get-property view-node :indent-level-pattern)
         indent-string (get-property view-node :indent-string)
         grammar (get-property view-node :grammar)
         lines (get-property view-node :lines)
         regions (get-property view-node :regions)
         layout (get-property view-node :layout)
         replacement-cursor-ranges (:cursor-ranges (get-property view-node :completion-context))
         {:keys [insert-string exit-ranges tab-triggers]} insertion
         replacement-lines (split-lines insert-string)
         replacement-line-counts (mapv count replacement-lines)
         insert-string-index->replacement-lines-cursor
         (fn insert-string-index->replacement-lines-cursor [^long i]
           (loop [row 0
                  col i]
             (let [^long row-len (replacement-line-counts row)]
               (if (< row-len col)
                 (recur (inc row) (dec (- col row-len)))
                 (data/->Cursor row col)))))
         insert-string-index-range->cursor-range
         (fn insert-string-index-range->cursor-range [[from to]]
           (data/->CursorRange
             (insert-string-index->replacement-lines-cursor from)
             (insert-string-index->replacement-lines-cursor to)))
         ;; cursor ranges and replacements
         splices (mapv
                   (fn [replacement-range]
                     (let [scope-id (gensym "tab-scope")
                           introduced-regions
                           (-> [(assoc (insert-string-index-range->cursor-range [0 (count insert-string)])
                                  :type :tab-trigger-scope
                                  :id scope-id)]
                               (cond->
                                 tab-triggers
                                 (into
                                   (comp
                                     (map-indexed
                                       (fn [i tab-trigger]
                                         (let [tab-trigger-contents (assoc (dissoc tab-trigger :ranges)
                                                                      :type :tab-trigger-word
                                                                      :scope-id scope-id
                                                                      :index i)]
                                           (eduction
                                             (map (fn [range]
                                                    (conj (insert-string-index-range->cursor-range range)
                                                          tab-trigger-contents)))
                                             (:ranges tab-trigger)))))
                                     cat)
                                   tab-triggers)

                                 exit-ranges
                                 (into
                                   (map (fn [index-range]
                                          (assoc (insert-string-index-range->cursor-range index-range)
                                            :type :tab-trigger-exit
                                            :scope-id scope-id)))
                                   exit-ranges))
                               sort
                               vec)]
                       [replacement-range replacement-lines introduced-regions]))
                   replacement-cursor-ranges)
         tab-scope-ids (into #{}
                             (comp
                               (mapcat (fn [[_ _ regions]]
                                         regions))
                               (keep (fn [{:keys [type] :as region}]
                                       (when (= :tab-trigger-scope type)
                                         (:id region)))))
                             splices)
         introduced-region? (fn [{:keys [type] :as region}]
                              (case type
                                :tab-trigger-scope (contains? tab-scope-ids (:id region))
                                (:tab-trigger-word :tab-trigger-exit) (contains? tab-scope-ids (:scope-id region))
                                false))
         props (data/replace-typed-chars indent-level-pattern indent-string grammar lines regions layout splices)]
     (when (some? props)
       (hide-suggestions! view-node)
       (let [cursor-ranges (:cursor-ranges props)
             regions (:regions props)
             new-cursor-ranges (cond
                                 tab-triggers
                                 (into []
                                       (comp
                                         (filter #(and (= :tab-trigger-word (:type %))
                                                       (zero? ^long (:index %))
                                                       (tab-scope-ids (:scope-id %))))
                                         (map data/sanitize-cursor-range))
                                       regions)

                                 exit-ranges
                                 (into []
                                       (comp
                                         (filter #(and (= :tab-trigger-exit (:type %))
                                                       (tab-scope-ids (:scope-id %))))
                                         (map data/sanitize-cursor-range))
                                       regions)

                                 :else
                                 (mapv data/cursor-range-end-range cursor-ranges))]
         (set-properties! view-node nil
                          (-> props
                              (assoc :cursor-ranges new-cursor-ranges
                                     :regions (if tab-triggers
                                                ;; remove other tab-trigger-related regions
                                                (into []
                                                      (remove #(and (tab-trigger-related-region? %)
                                                                    (not (introduced-region? %))))
                                                      regions)
                                                ;; no triggers: remove introduced regions
                                                (into [] (remove introduced-region?) regions)))
                              (data/frame-cursor layout))))))))

(def ^:private ext-with-list-view-props
  (fx/make-ext-with-props
    {:items (prop/make
              (mutator/setter (fn [^ListView view [_key items]]
                                ;; force items change on key change since text
                                ;; runs are in meta (equality not affected), but
                                ;; we want to show them when they change anyway
                                (.setAll (.getItems view) ^Collection items)))
              fx.lifecycle/scalar)
     :selected-index (prop/make
                       (mutator/setter
                         (fn [^ListView view [_key index]]
                           (.clearAndSelect (.getSelectionModel view) (or index 0))
                           (when-not index (.scrollTo view 0))))
                       fx.lifecycle/scalar)}))

(defn- completion-list-cell-view [completion]
  (if completion
    (let [text (:display-string completion)
          matching-indices (fuzzy-choices/matching-indices completion)]
      {:graphic (fuzzy-choices/make-matched-text-flow-cljfx text matching-indices)
       :on-mouse-clicked {:event :accept :completion completion}})
    {}))

(defn- completion-popup-view
  [{:keys [completion-state canvas-repaint-info font font-name font-size
           visible-completion-ranges query screen-bounds]}]
  (let [{:keys [showing completions selected-index show-doc project]} completion-state
        item-count (count completions)]
    (if (or (not showing) (zero? item-count))
      {:fx/type fxui/ext-value :value nil}
      (let [{:keys [^Canvas canvas ^LayoutInfo layout lines]} canvas-repaint-info
            ^Point2D cursor-bottom (or (and (pos? (count visible-completion-ranges))
                                            (cursor-bottom layout lines (data/cursor-range-start (first visible-completion-ranges))))
                                       Point2D/ZERO)
            anchor (.localToScreen canvas cursor-bottom)
            glyph-metrics (.-glyph layout)
            ^double cell-height (data/line-height glyph-metrics)
            max-visible-completions-count 10
            text (doto (Text.) (.setFont font))
            min-completions-height (* (min 3 item-count) cell-height)
            min-completions-width 150.0
            [^Point2D anchor ^Rectangle2D screen-bounds]
            (transduce (map (fn [^Rectangle2D bounds]
                              (pair
                                (Point2D.
                                  (-> (.getX anchor)
                                      (min (- (.getMaxX bounds) min-completions-width))
                                      (max (.getMinX bounds)))
                                  (-> (.getY anchor)
                                      (min (- (.getMaxY bounds) min-completions-height))
                                      (max (.getMinY bounds))))
                                bounds)))
                       (partial min-key #(.distance anchor ^Point2D (key %)))
                       (pair (Point2D. Double/MAX_VALUE Double/MAX_VALUE) nil)
                       screen-bounds)
            max-completions-width (min 700.0 (- (.getMaxX screen-bounds) (.getX anchor)))
            max-completions-height (min (* cell-height max-visible-completions-count)
                                        (-> (- (.getMaxY screen-bounds) (.getY anchor))
                                            (/ cell-height)
                                            Math/floor
                                            (* cell-height)))
            display-string-widths (eduction
                                    (take 512)
                                    (map #(-> text
                                              (doto (.setText (:display-string %)))
                                              .getLayoutBounds
                                              .getWidth))
                                    completions)
            ^double max-display-string-width (reduce max 0.0 display-string-widths)
            completions-width (-> (+ 12.0                      ;; paddings
                                     (if (< max-visible-completions-count item-count)
                                       10.0                    ;; scroll bar
                                       0.0)
                                     ;; clamp
                                     (max min-completions-width max-display-string-width))
                                  (min max-completions-width))
            refresh-key [query completions]
            selected-completion (completions (or selected-index 0))]
        {:fx/type fx/ext-let-refs
         :refs {:content {:fx/type fx.stack-pane/lifecycle
                          :style-class "flat-list-container"
                          :stylesheets [(str (io/resource "editor.css"))]
                          :children
                          [{:fx/type ext-with-list-view-props
                            :props {:selected-index [refresh-key selected-index]}
                            :desc
                            {:fx/type ext-with-list-view-props
                             :props {:items [refresh-key completions]}
                             :desc
                             {:fx/type fx.ext.list-view/with-selection-props
                              :props {:on-selected-index-changed {:event :select}}
                              :desc
                              {:fx/type fx.list-view/lifecycle
                               :style {:-fx-font-family (str \" font-name \") :-fx-font-size font-size}
                               :id "fuzzy-choices-list-view"
                               :style-class "flat-list-view"
                               :fixed-cell-size cell-height
                               :event-filter {:event :completion-list-view-event-filter}
                               :min-width completions-width
                               :pref-width completions-width
                               :max-width completions-width
                               :min-height min-completions-height
                               :pref-height (* cell-height item-count)
                               :max-height (min (* cell-height max-visible-completions-count) max-completions-height)
                               :cell-factory {:fx/cell-type fx.list-cell/lifecycle
                                              :describe completion-list-cell-view}}}}}]}}
         :desc
         {:fx/type fx/ext-many
          :desc
          (cond->
            [{:fx/type fxui/with-popup
              :desc {:fx/type fxui/ext-value :value canvas}
              :anchor-x (- (.getX anchor) 12.0)
              :anchor-y (- (.getY anchor) 4.0)
              :anchor-location :window-top-left
              :showing showing
              :auto-fix false
              :auto-hide true
              :on-auto-hide {:event :auto-hide}
              :hide-on-escape false
              :content [{:fx/type fx/ext-get-ref :ref :content}]}]
            show-doc
            (conj
              (let [pref-doc-width 350.0
                    doc-max-height (- (.getMaxY screen-bounds) (.getY anchor))
                    spacing 6.0
                    left-space (- (.getX anchor) spacing)
                    right-space (- (.getMaxX screen-bounds)
                                   (+ (.getX anchor) completions-width spacing))
                    align-right (or (<= pref-doc-width right-space)
                                    (<= left-space right-space))
                    doc-width (if align-right
                                (min pref-doc-width right-space)
                                (min pref-doc-width left-space))
                    {:keys [detail doc type]} selected-completion
                    small-string (when-let [small-text (or detail (some-> type name (string/replace "-" " ")))]
                                   (format "<small>%s</small>" small-text))
                    doc-string (when doc
                                 (case (:type doc)
                                   :markdown (:value doc)
                                   :plaintext (format "<pre>%s</pre>" (:value doc))))]
                {:fx/type fxui/with-popup
                 :desc {:fx/type fx/ext-get-ref :ref :content}
                 :anchor-x (let [x (- (.getX anchor) 12.0)]
                             (if align-right
                               (+ x completions-width spacing)
                               (- x doc-width spacing)))
                 :anchor-y (- (.getY anchor) 5.0)
                 :anchor-location :window-top-left
                 :showing true
                 :auto-fix false
                 :auto-hide false
                 :hide-on-escape false
                 :content [{:fx/type fx.stack-pane/lifecycle
                            :stylesheets [(str (io/resource "editor.css"))]
                            :children [{:fx/type fx.region/lifecycle
                                        :style-class "flat-list-doc-background"}
                                       (cond->
                                         {:fx/type markdown/view
                                          :base-url (:base-url doc)
                                          :event-filter {:event :doc-event-filter}
                                          :max-width doc-width
                                          :max-height doc-max-height
                                          :project project
                                          :content (cond
                                                     (and small-string doc-string) (str small-string "\n\n" doc-string)
                                                     small-string small-string
                                                     doc-string doc-string
                                                     :else "<small>no documentation</small>")}
                                         (not align-right)
                                         (assoc :min-width doc-width))]}]})))}}))))

(defn- handle-completion-popup-event [view-node e]
  (case (:event e)
    :doc-event-filter
    (let [e (:fx/event e)]
      (when (instance? KeyEvent e)
        (let [^KeyEvent e e
              ^Node source (.getSource e)
              ^PopupWindow window (.getWindow (.getScene source))
              target (.getFocusOwner (.getScene (.getOwnerWindow window)))]
          (ui/send-event! target e)
          (.consume e))))

    :auto-hide
    (hide-suggestions! view-node)

    :completion-list-view-event-filter
    (let [e (:fx/event e)]
      (when (instance? KeyEvent e)
        (let [^KeyEvent e e
              code (.getCode e)]
          ;; redirect everything except arrows to canvas
          (when-not (or (= KeyCode/UP code)
                        (= KeyCode/DOWN code)
                        (= KeyCode/PAGE_UP code)
                        (= KeyCode/PAGE_DOWN code))
            (ui/send-event! (get-property view-node :canvas) e)
            (.consume e)))))

    :select
    (g/update-property! view-node :completion-state
                        (fn [{:keys [completions] :as completion-state}]
                          (let [max-index (dec (count completions))]
                            (if (neg? max-index)
                              completion-state
                              (assoc completion-state :selected-index (min ^long (:fx/event e) max-index))))))
    :accept
    (accept-suggestion! view-node (code-completion/insertion (:completion e)))))

;; -----------------------------------------------------------------------------

(defn move! [view-node move-type cursor-type]
  (hide-suggestions! view-node)
  (set-properties! view-node move-type
                   (data/move (get-property view-node :lines)
                              (get-property view-node :cursor-ranges)
                              (get-property view-node :layout)
                              move-type
                              cursor-type)))

(defn page-up! [view-node move-type]
  (hide-suggestions! view-node)
  (set-properties! view-node move-type
                   (data/page-up (get-property view-node :lines)
                                 (get-property view-node :cursor-ranges)
                                 (get-property view-node :scroll-y)
                                 (get-property view-node :layout)
                                 move-type)))

(defn page-down! [view-node move-type]
  (hide-suggestions! view-node)
  (set-properties! view-node move-type
                   (data/page-down (get-property view-node :lines)
                                   (get-property view-node :cursor-ranges)
                                   (get-property view-node :scroll-y)
                                   (get-property view-node :layout)
                                   move-type)))

(defn delete! [view-node delete-type]
  (let [cursor-ranges (get-property view-node :cursor-ranges)
        cursor-ranges-empty (every? data/cursor-range-empty? cursor-ranges)
        single-character-backspace (and cursor-ranges-empty (= :delete-before delete-type))
        undo-grouping (when cursor-ranges-empty :typing)
        delete-fn (case delete-type
                    :delete-before data/delete-character-before-cursor
                    :delete-word-before data/delete-word-before-cursor
                    :delete-after data/delete-character-after-cursor
                    :delete-word-after data/delete-word-after-cursor)]
    (set-properties! view-node undo-grouping
                     (data/delete (get-property view-node :lines)
                                  cursor-ranges
                                  (get-property view-node :regions)
                                  (get-property view-node :layout)
                                  delete-fn))
    (if (and single-character-backspace
             (suggestions-shown? view-node)
             (implies-completions? view-node))
      (show-suggestions! view-node false)
      (hide-suggestions! view-node))))

(defn toggle-comment! [view-node]
  (set-properties! view-node nil
                   (data/toggle-comment (get-property view-node :lines)
                                        (get-property view-node :regions)
                                        (get-property view-node :cursor-ranges)
                                        (:line-comment (get-property view-node :grammar)))))

(defn select-all! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node :selection
                   (data/select-all (get-property view-node :lines)
                                    (get-property view-node :cursor-ranges))))

(defn select-next-occurrence! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node :selection
                   (data/select-next-occurrence (get-property view-node :lines)
                                                (get-property view-node :cursor-ranges)
                                                (get-property view-node :layout))))

(defn- indent! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/indent (get-property view-node :indent-level-pattern)
                                (get-property view-node :indent-string)
                                (get-property view-node :grammar)
                                (get-property view-node :lines)
                                (get-property view-node :cursor-ranges)
                                (get-property view-node :regions)
                                (get-property view-node :layout))))

(defn- deindent! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/deindent (get-property view-node :lines)
                                  (get-property view-node :cursor-ranges)
                                  (get-property view-node :regions)
                                  (get-property view-node :tab-spaces))))

(defn- tab! [view-node]
  (cond
    (suggestions-visible? view-node)
    (accept-suggestion! view-node)

    (in-tab-trigger? view-node)
    (next-tab-trigger! view-node)

    :else
    (indent! view-node)))

(defn- shift-tab! [view-node]
  (cond
    (in-tab-trigger? view-node)
    (prev-tab-trigger! view-node)

    :else
    (deindent! view-node)))

(handler/defhandler :tab :code-view
  (run [view-node] (tab! view-node)))

(handler/defhandler :backwards-tab-trigger :code-view
  (run [view-node] (shift-tab! view-node)))

(handler/defhandler :proposals :code-view
  (run [view-node] (show-suggestions! view-node true)))

;; -----------------------------------------------------------------------------

(defn- insert-text! [view-node typed]
  (let [undo-grouping (if (= "\r" typed) :newline :typing)
        selected-suggestion (selected-suggestion view-node)
        grammar (get-property view-node :grammar)
        [insert-typed show-suggestions]
        (cond
          (and selected-suggestion
               (or (= "\r" typed)
                   (let [commit-characters (or (:commit-characters selected-suggestion)
                                               (get-in grammar [:commit-characters (:type selected-suggestion)]))]
                     (contains? commit-characters typed))))
          (let [insertion (code-completion/insertion selected-suggestion)]
            (do (accept-suggestion! view-node insertion)
                [;; insert-typed
                 (not
                   ;; exclude typed when...
                   (or (= typed "\r")
                       ;; At this point, we know we typed a commit character.
                       ;; If there are tab stops, and we typed a character
                       ;; before the tab stop, we assume the commit character
                       ;; is a shortcut for accepting a completion and jumping
                       ;; into the tab stop, e.g. foo($1) + "(" => don't
                       ;; insert. Otherwise, we insert typed, e.g.:
                       ;; - foo($1) + "{" => the typed character is expected
                       ;;   to be inside the tab stop, for example, when foo
                       ;;   expects a hash map
                       ;; - vmath + "." => the typed character is expected
                       ;;   to be after the snippet.
                       (when-let [^long i (->> insertion :tab-triggers first :ranges first first)]
                         (when (pos? i)
                           (= typed (subs (:insert-string insertion) (dec i) i))))))
                 ;; show-suggestions
                 (not
                   ;; hide suggestions when entering new scope
                   (#{"[" "(" "{"} typed))]))

          (data/typing-deindents-line? grammar (get-property view-node :lines) (get-property view-node :cursor-ranges) typed)
          [true false]

          :else
          [true true])]
    (when insert-typed
      (when (set-properties! view-node undo-grouping
                             (data/key-typed (get-property view-node :indent-level-pattern)
                                             (get-property view-node :indent-string)
                                             grammar
                                             ;; NOTE: don't move :lines and :cursor-ranges
                                             ;; to the let above the
                                             ;; [insert-typed show-suggestion] binding:
                                             ;; accepting suggestions might change them!
                                             (get-property view-node :lines)
                                             (get-property view-node :cursor-ranges)
                                             (get-property view-node :regions)
                                             (get-property view-node :layout)
                                             typed))
        (if (and show-suggestions (implies-completions? view-node))
          (show-suggestions! view-node false)
          (hide-suggestions! view-node))))))

(defn handle-key-pressed! [view-node ^KeyEvent event]
  ;; Only handle bare key events that cannot be bound to handlers here.
  (when-not (or (.isAltDown event)
                (.isMetaDown event)
                (.isShiftDown event)
                (.isShortcutDown event))
    (when (not= ::unhandled
                (condp = (.getCode event)
                  KeyCode/HOME  (move! view-node :navigation :home)
                  KeyCode/END   (move! view-node :navigation :end)
                  KeyCode/LEFT  (move! view-node :navigation :left)
                  KeyCode/RIGHT (move! view-node :navigation :right)
                  KeyCode/UP    (move! view-node :navigation :up)
                  KeyCode/DOWN  (move! view-node :navigation :down)
                  KeyCode/ENTER (insert-text! view-node "\r")
                  ::unhandled))
      (.consume event))))

(defn- typable-key-event?
  [^KeyEvent e]
  (-> e
      keymap/key-event->map
      keymap/typable?))

(defn handle-key-typed! [view-node ^KeyEvent event]
  (.consume event)
  (let [character (.getCharacter event)]
    (when (and (typable-key-event? event)
               ;; Ignore characters in the control range and the ASCII delete
               ;; as it is done by JavaFX in `TextInputControlBehavior`'s
               ;; `defaultKeyTyped` method.
               (pos? (.length character))
               (> (int (.charAt character 0)) 0x1f)
               (not= (int (.charAt character 0)) 0x7f))
      (insert-text! view-node character))))

(defn- refresh-mouse-cursor! [view-node ^MouseEvent event]
  (let [hovered-element (get-property view-node :hovered-element)
        gesture-type (:type (get-property view-node :gesture-start))
        ^LayoutInfo layout (get-property view-node :layout)
        ^Node node (.getTarget event)
        x (.getX event)
        y (.getY event)
        cursor (cond
                 (or (= :ui-element (:type hovered-element))
                     (= :scroll-tab-x-drag gesture-type)
                     (= :scroll-tab-y-drag gesture-type)
                     (= :scroll-bar-y-hold-up gesture-type)
                     (= :scroll-bar-y-hold-down gesture-type))
                 javafx.scene.Cursor/DEFAULT

                 (some? (:on-click! (:region hovered-element)))
                 javafx.scene.Cursor/HAND

                 (data/rect-contains? (.canvas layout) x y)
                 javafx.scene.Cursor/TEXT

                 :else
                 javafx.scene.Cursor/DEFAULT)]
    ;; The cursor refresh appears buggy at the moment.
    ;; Calling setCursor with DISAPPEAR before setting the cursor forces it to refresh.
    (when (not= cursor (.getCursor node))
      (.setCursor node javafx.scene.Cursor/DISAPPEAR)
      (.setCursor node cursor))))

(defn handle-mouse-pressed! [view-node ^MouseEvent event]
  (.consume event)
  (.requestFocus ^Node (.getTarget event))
  (refresh-mouse-cursor! view-node event)
  (hide-suggestions! view-node)
  (set-properties! view-node (if (< 1 (.getClickCount event)) :selection :navigation)
                   (data/mouse-pressed (get-property view-node :lines)
                                       (get-property view-node :cursor-ranges)
                                       (get-property view-node :regions)
                                       (get-property view-node :layout)
                                       (get-property view-node :minimap-layout)
                                       (mouse-button event)
                                       (.getClickCount event)
                                       (.getX event)
                                       (.getY event)
                                       (.isAltDown event)
                                       (.isShiftDown event)
                                       (.isShortcutDown event))))

(defn handle-mouse-moved! [view-node ^MouseDragEvent event]
  (.consume event)
  (set-properties! view-node :selection
                   (data/mouse-moved (get-property view-node :lines)
                                     (get-property view-node :cursor-ranges)
                                     (get-property view-node :visible-regions)
                                     (get-property view-node :layout)
                                     (get-property view-node :minimap-layout)
                                     (get-property view-node :gesture-start)
                                     (get-property view-node :hovered-element)
                                     (.getX event)
                                     (.getY event)))
  (refresh-mouse-cursor! view-node event))

(defn handle-mouse-released! [view-node ^MouseEvent event]
  (.consume event)
  (when-some [hovered-region (:region (get-property view-node :hovered-element))]
    (when-some [on-click! (:on-click! hovered-region)]
      (on-click! hovered-region event)))
  (refresh-mouse-cursor! view-node event)
  (set-properties! view-node :selection
                   (data/mouse-released (get-property view-node :lines)
                                        (get-property view-node :cursor-ranges)
                                        (get-property view-node :visible-regions)
                                        (get-property view-node :layout)
                                        (get-property view-node :minimap-layout)
                                        (get-property view-node :gesture-start)
                                        (mouse-button event)
                                        (.getX event)
                                        (.getY event))))

(defn handle-mouse-exited! [view-node ^MouseEvent event]
  (.consume event)
  (set-properties! view-node :selection
                   (data/mouse-exited (get-property view-node :gesture-start)
                                      (get-property view-node :hovered-element))))

(defn handle-scroll! [view-node ^ScrollEvent event]
  (.consume event)
  (when (set-properties! view-node :navigation
                         (data/scroll (get-property view-node :lines)
                                      (get-property view-node :scroll-x)
                                      (get-property view-node :scroll-y)
                                      (get-property view-node :layout)
                                      (get-property view-node :gesture-start)
                                      (.getDeltaX event)
                                      (.getDeltaY event)))
    (hide-suggestions! view-node)))

;; -----------------------------------------------------------------------------

(defn has-selection? [view-node evaluation-context]
  (not-every? data/cursor-range-empty?
              (get-property view-node :cursor-ranges evaluation-context)))

(defn can-paste? [view-node clipboard evaluation-context]
  (data/can-paste? (get-property view-node :cursor-ranges evaluation-context) clipboard))

(defn cut! [view-node clipboard]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/cut! (get-property view-node :lines)
                              (get-property view-node :cursor-ranges)
                              (get-property view-node :regions)
                              (get-property view-node :layout)
                              clipboard)))

(defn copy! [view-node clipboard]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/copy! (get-property view-node :lines)
                               (get-property view-node :cursor-ranges)
                               clipboard)))

(defn paste! [view-node clipboard]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/paste (get-property view-node :indent-level-pattern)
                               (get-property view-node :indent-string)
                               (get-property view-node :grammar)
                               (get-property view-node :lines)
                               (get-property view-node :cursor-ranges)
                               (get-property view-node :regions)
                               (get-property view-node :layout)
                               clipboard)))

(defn split-selection-into-lines! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node :selection
                   (data/split-selection-into-lines (get-property view-node :lines)
                                                    (get-property view-node :cursor-ranges))))

(handler/defhandler :select-up :code-view
  (run [view-node] (move! view-node :selection :up)))

(handler/defhandler :select-down :code-view
  (run [view-node] (move! view-node :selection :down)))

(handler/defhandler :select-left :code-view
  (run [view-node] (move! view-node :selection :left)))

(handler/defhandler :select-right :code-view
  (run [view-node] (move! view-node :selection :right)))

(handler/defhandler :prev-word :code-view
  (run [view-node] (move! view-node :navigation :prev-word)))

(handler/defhandler :select-prev-word :code-view
  (run [view-node] (move! view-node :selection :prev-word)))

(handler/defhandler :next-word :code-view
  (run [view-node] (move! view-node :navigation :next-word)))

(handler/defhandler :select-next-word :code-view
  (run [view-node] (move! view-node :selection :next-word)))

(handler/defhandler :beginning-of-line :code-view
  (run [view-node] (move! view-node :navigation :line-start)))

(handler/defhandler :select-beginning-of-line :code-view
  (run [view-node] (move! view-node :selection :line-start)))

(handler/defhandler :beginning-of-line-text :code-view
  (run [view-node] (move! view-node :navigation :home)))

(handler/defhandler :select-beginning-of-line-text :code-view
  (run [view-node] (move! view-node :selection :home)))

(handler/defhandler :end-of-line :code-view
  (run [view-node] (move! view-node :navigation :end)))

(handler/defhandler :select-end-of-line :code-view
  (run [view-node] (move! view-node :selection :end)))

(handler/defhandler :page-up :code-view
  (run [view-node] (page-up! view-node :navigation)))

(handler/defhandler :select-page-up :code-view
  (run [view-node] (page-up! view-node :selection)))

(handler/defhandler :page-down :code-view
  (run [view-node] (page-down! view-node :navigation)))

(handler/defhandler :select-page-down :code-view
  (run [view-node] (page-down! view-node :selection)))

(handler/defhandler :beginning-of-file :code-view
  (run [view-node] (move! view-node :navigation :file-start)))

(handler/defhandler :select-beginning-of-file :code-view
  (run [view-node] (move! view-node :selection :file-start)))

(handler/defhandler :end-of-file :code-view
  (run [view-node] (move! view-node :navigation :file-end)))

(handler/defhandler :select-end-of-file :code-view
  (run [view-node] (move! view-node :selection :file-end)))

(handler/defhandler :cut :code-view
  (enabled? [view-node evaluation-context]
            (has-selection? view-node evaluation-context))
  (run [view-node clipboard] (cut! view-node clipboard)))

(handler/defhandler :copy :code-view
  (enabled? [view-node evaluation-context]
            (has-selection? view-node evaluation-context))
  (run [view-node clipboard] (copy! view-node clipboard)))

(handler/defhandler :paste :code-view
  (enabled? [view-node clipboard evaluation-context]
            (can-paste? view-node clipboard evaluation-context))
  (run [view-node clipboard] (paste! view-node clipboard)))

(handler/defhandler :select-all :code-view
  (run [view-node] (select-all! view-node)))

(handler/defhandler :delete :code-view
  (run [view-node] (delete! view-node :delete-after)))

(handler/defhandler :toggle-comment :code-view
  (active? [view-node evaluation-context]
           (contains? (get-property view-node :grammar evaluation-context) :line-comment))
  (run [view-node] (toggle-comment! view-node)))

(handler/defhandler :delete-backward :code-view
  (run [view-node] (delete! view-node :delete-before)))

(handler/defhandler :delete-prev-word :code-view
  (run [view-node] (delete! view-node :delete-word-before)))

(handler/defhandler :delete-next-word :code-view
  (run [view-node] (delete! view-node :delete-word-after)))

(handler/defhandler :select-next-occurrence :code-view
  (run [view-node] (select-next-occurrence! view-node)))

(handler/defhandler :select-next-occurrence :code-view-tools
  (run [view-node] (select-next-occurrence! view-node)))

(handler/defhandler :split-selection-into-lines :code-view
  (run [view-node] (split-selection-into-lines! view-node)))

(handler/defhandler :toggle-breakpoint :code-view
  (run [view-node]
       (let [lines (get-property view-node :lines)
             cursor-ranges (get-property view-node :cursor-ranges)
             regions (get-property view-node :regions)
             breakpoint-rows (data/cursor-ranges->start-rows lines cursor-ranges)]
         (set-properties! view-node nil
                          (data/toggle-breakpoint lines
                                                  regions
                                                  breakpoint-rows)))))

(handler/defhandler :edit-breakpoint :code-view
  (run [view-node]
    (let [lines (get-property view-node :lines)
          cursor-ranges (get-property view-node :cursor-ranges)
          regions (get-property view-node :regions)]
      (set-properties!
        view-node
        nil
        (data/edit-breakpoint-from-single-cursor-range lines cursor-ranges regions)))))

(handler/defhandler :reindent :code-view
  (enabled? [view-node evaluation-context]
            (not-every? data/cursor-range-empty?
                        (get-property view-node :cursor-ranges evaluation-context)))
  (run [view-node]
       (set-properties! view-node nil
                        (data/reindent (get-property view-node :indent-level-pattern)
                                       (get-property view-node :indent-string)
                                       (get-property view-node :grammar)
                                       (get-property view-node :lines)
                                       (get-property view-node :cursor-ranges)
                                       (get-property view-node :regions)
                                       (get-property view-node :layout)))))

(handler/defhandler :convert-indentation :code-view
  (run [view-node user-data]
       (set-properties! view-node nil
                        (data/convert-indentation (get-property view-node :indent-type)
                                                  user-data
                                                  (get-property view-node :lines)
                                                  (get-property view-node :cursor-ranges)
                                                  (get-property view-node :regions)))))

(defn- show-goto-popup! [view-node open-resource-fn results]
  (g/with-auto-evaluation-context evaluation-context
    (let [cursor (data/CursorRange->Cursor (first (get-property view-node :cursor-ranges evaluation-context)))
          ^Canvas canvas (get-property view-node :canvas evaluation-context)
          ^LayoutInfo layout (get-property view-node :layout evaluation-context)
          lines (get-property view-node :lines evaluation-context)
          cursor-bottom (cursor-bottom layout lines cursor)
          font-name (get-property view-node :font-name evaluation-context)
          font-size (get-property view-node :font-size evaluation-context)
          list-view (popup/make-choices-list-view
                      (data/line-height (.-glyph layout))
                      (fn [{:keys [resource cursor-range]}]
                        {:graphic
                         (doto (Text.
                                 (str (resource/proj-path resource)
                                      ":"
                                      (data/CursorRange->line-number cursor-range)))
                           (.setFont (Font/font font-name font-size)))}))
          [width height] (popup/update-list-view! list-view 200.0 results 0)
          anchor (pref-suggestions-popup-position canvas width height cursor-bottom)
          popup (doto (popup/make-choices-popup canvas list-view)
                  (.addEventHandler Event/ANY (ui/event-handler event (.consume event))))
          accept! (fn [{:keys [resource cursor-range]}]
                    (.hide popup)
                    (open-resource-fn resource {:cursor-range cursor-range}))]
      (hide-suggestions! view-node)
      (doto list-view
        ui/apply-css!
        (.setOnMouseClicked
          (ui/event-handler event
            (when-let [item (ui/cell-item-under-mouse event)]
              (accept! item))))
        (.setOnKeyPressed
          (ui/event-handler event
            (let [^KeyEvent event event]
              (when (and (= KeyEvent/KEY_PRESSED (.getEventType event)))
                (condp = (.getCode event)
                  KeyCode/ENTER (accept! (first (ui/selection list-view)))
                  KeyCode/ESCAPE (.hide popup)
                  nil))))))
      (.show popup (.getWindow (.getScene canvas)) (.getX anchor) (.getY anchor))
      (popup/update-list-view! list-view 200.0 results 0))))

(defn- show-no-language-server-for-resource-language-notification! [resource]
  (let [language (resource/language resource)]
    (notifications/show!
      (workspace/notifications (resource/workspace resource))
      {:type :warning
       :id [::no-lsp language]
       :text (format "Cannot perform this action because there is no LSP Language Server running for the '%s' language"
                     language)
       :actions [{:text "About LSP in Defold"
                  :on-action #(ui/open-url "https://forum.defold.com/t/linting-in-the-code-editor/72465")}]})))

(handler/defhandler :goto-definition :code-view
  (enabled? [view-node evaluation-context]
    (let [resource-node (get-property view-node :resource-node evaluation-context)
          resource (g/node-value resource-node :resource evaluation-context)]
      (resource/file-resource? resource)))
  (run [view-node user-data open-resource-fn]
    (let [resource-node (get-property view-node :resource-node)
          resource (g/node-value resource-node :resource)
          lsp (lsp/get-node-lsp resource-node)]
      (if (lsp/has-language-servers-running-for-language? lsp (resource/language resource))
        (lsp/goto-definition!
          lsp
          resource
          (data/CursorRange->Cursor (first (get-property view-node :cursor-ranges)))
          (fn [results]
            (fx/on-fx-thread
              (case (count results)
                0 nil
                1 (open-resource-fn
                    (:resource (first results))
                    {:cursor-range (:cursor-range (first results))})
                (show-goto-popup! view-node open-resource-fn results)))))
        (show-no-language-server-for-resource-language-notification! resource)))))

(handler/defhandler :find-references :code-view
  (enabled? [view-node evaluation-context]
    (let [resource-node (get-property view-node :resource-node evaluation-context)
          resource (g/node-value resource-node :resource evaluation-context)]
      (resource/file-resource? resource)))
  (run [view-node user-data open-resource-fn]
    (let [resource-node (get-property view-node :resource-node)
          lsp (lsp/get-node-lsp resource-node)
          resource (g/node-value resource-node :resource)]
      (if (lsp/has-language-servers-running-for-language? lsp (resource/language resource))
        (lsp/find-references!
          lsp
          resource
          (data/CursorRange->Cursor (first (get-property view-node :cursor-ranges)))
          (fn [results]
            (when (pos? (count results))
              (fx/on-fx-thread
                (show-goto-popup! view-node open-resource-fn results)))))
        (show-no-language-server-for-resource-language-notification! resource)))))

;; -----------------------------------------------------------------------------
;; Sort Lines
;; -----------------------------------------------------------------------------

(defn- can-sort-lines? [view-node evaluation-context]
  (some data/cursor-range-multi-line? (get-property view-node :cursor-ranges evaluation-context)))

(defn- sort-lines! [view-node sort-key-fn]
  (set-properties! view-node nil
                   (data/sort-lines (get-property view-node :lines)
                                    (get-property view-node :cursor-ranges)
                                    (get-property view-node :regions)
                                    sort-key-fn)))

(handler/defhandler :sort-lines :code-view
  (enabled? [view-node evaluation-context] (can-sort-lines? view-node evaluation-context))
  (run [view-node] (sort-lines! view-node string/lower-case)))

(handler/defhandler :sort-lines-case-sensitive :code-view
  (enabled? [view-node evaluation-context] (can-sort-lines? view-node evaluation-context))
  (run [view-node] (sort-lines! view-node identity)))

;; -----------------------------------------------------------------------------
;; Properties shared among views
;; -----------------------------------------------------------------------------

;; WARNING:
;; Observing or binding to an observable that lives longer than the observer will
;; cause a memory leak. You must manually unhook them or use weak listeners.
;; Source: https://community.oracle.com/message/10360893#10360893

(defonce ^:private ^SimpleObjectProperty bar-ui-type-property (SimpleObjectProperty. :hidden))
(defonce ^:private ^SimpleStringProperty find-term-property (SimpleStringProperty. ""))
(defonce ^:private ^SimpleStringProperty find-replacement-property (SimpleStringProperty. ""))
(defonce ^:private ^SimpleBooleanProperty find-whole-word-property (SimpleBooleanProperty. false))
(defonce ^:private ^SimpleBooleanProperty find-case-sensitive-property (SimpleBooleanProperty. false))
(defonce ^:private ^SimpleBooleanProperty find-wrap-property (SimpleBooleanProperty. true))
(defonce ^:private ^SimpleDoubleProperty font-size-property (SimpleDoubleProperty. default-font-size))
(defonce ^:private ^SimpleStringProperty font-name-property (SimpleStringProperty. ""))
(defonce ^:private ^SimpleBooleanProperty visible-indentation-guides-property (SimpleBooleanProperty. true))
(defonce ^:private ^SimpleBooleanProperty visible-minimap-property (SimpleBooleanProperty. true))
(defonce ^:private ^SimpleBooleanProperty visible-whitespace-property (SimpleBooleanProperty. true))

(defonce ^:private ^ObjectBinding highlighted-find-term-property
  (b/if (b/or (b/= :find bar-ui-type-property)
              (b/= :replace bar-ui-type-property))
    find-term-property
    ""))

(defn- init-property-and-bind-preference! [^Property property prefs preference default]
  (.setValue property (prefs/get-prefs prefs preference default))
  (ui/observe property (fn [_ _ new] (prefs/set-prefs prefs preference new))))

(defn initialize! [prefs]
  (init-property-and-bind-preference! find-term-property prefs "code-editor-find-term" "")
  (init-property-and-bind-preference! find-replacement-property prefs "code-editor-find-replacement" "")
  (init-property-and-bind-preference! find-whole-word-property prefs "code-editor-find-whole-word" false)
  (init-property-and-bind-preference! find-case-sensitive-property prefs "code-editor-find-case-sensitive" false)
  (init-property-and-bind-preference! find-wrap-property prefs "code-editor-find-wrap" true)
  (init-property-and-bind-preference! font-size-property prefs "code-editor-font-size" default-font-size)
  (init-property-and-bind-preference! font-name-property prefs "code-editor-font-name" "")
  (init-property-and-bind-preference! visible-indentation-guides-property prefs "code-editor-visible-indentation-guides" true)
  (init-property-and-bind-preference! visible-minimap-property prefs "code-editor-visible-minimap" true)
  (init-property-and-bind-preference! visible-whitespace-property prefs "code-editor-visible-whitespace" true)
  (when (clojure.string/blank? (.getValue font-name-property))
    (.setValue font-name-property "Dejavu Sans Mono")))

;; -----------------------------------------------------------------------------
;; View Settings
;; -----------------------------------------------------------------------------

(handler/defhandler :zoom-out :code-view-tools
  (enabled? [] (<= 4.0 ^double (.getValue font-size-property)))
  (run [] (when (<= 4.0 ^double (.getValue font-size-property))
            (.setValue font-size-property (dec ^double (.getValue font-size-property))))))

(handler/defhandler :zoom-in :code-view-tools
  (enabled? [] (>= 32.0 ^double (.getValue font-size-property)))
  (run [] (when (>= 32.0 ^double (.getValue font-size-property))
            (.setValue font-size-property (inc ^double (.getValue font-size-property))))))

(handler/defhandler :reset-zoom :code-view-tools
  (run [] (.setValue font-size-property default-font-size)))

(handler/defhandler :toggle-indentation-guides :code-view-tools
  (run [] (.setValue visible-indentation-guides-property (not (.getValue visible-indentation-guides-property))))
  (state [] (.getValue visible-indentation-guides-property)))

(handler/defhandler :toggle-minimap :code-view-tools
  (run [] (.setValue visible-minimap-property (not (.getValue visible-minimap-property))))
  (state [] (.getValue visible-minimap-property)))

(handler/defhandler :toggle-visible-whitespace :code-view-tools
  (run [] (.setValue visible-whitespace-property (not (.getValue visible-whitespace-property))))
  (state [] (.getValue visible-whitespace-property)))

;; -----------------------------------------------------------------------------
;; Go to Line
;; -----------------------------------------------------------------------------

(defn- bar-ui-visible? []
  (not= :hidden (.getValue bar-ui-type-property)))

(defn- set-bar-ui-type! [ui-type]
  (case ui-type (:hidden :goto-line :find :replace) (.setValue bar-ui-type-property ui-type)))

(defn- focus-code-editor! [view-node]
  (let [^Canvas canvas (g/node-value view-node :canvas)]
    (.requestFocus canvas)))

(defn- try-parse-row [^long document-row-count ^String value]
  ;; Returns nil for an empty string.
  ;; Returns a zero-based row index for valid line numbers, starting at one.
  ;; Returns a string describing the problem for invalid input.
  (when-not (empty? value)
    (try
      (let [line-number (Long/parseLong value)]
        (dec (Math/max 1 (Math/min line-number document-row-count))))
      (catch NumberFormatException _
        "Input must be a number"))))

(defn- try-parse-goto-line-text
  ([view-node text]
   (g/with-auto-evaluation-context evaluation-context
     (try-parse-goto-line-text view-node text evaluation-context)))
  ([view-node ^String text evaluation-context]
   (let [lines (get-property view-node :lines evaluation-context)]
     (try-parse-row (count lines) text))))

(defn- try-parse-goto-line-bar-row
  ([view-node goto-line-bar]
   (g/with-auto-evaluation-context evaluation-context
     (try-parse-goto-line-bar-row view-node goto-line-bar evaluation-context)))
  ([view-node ^GridPane goto-line-bar evaluation-context]
   (ui/with-controls goto-line-bar [^TextField line-field]
     (let [maybe-row (try-parse-goto-line-text view-node (.getText line-field) evaluation-context)]
       (when (number? maybe-row)
         maybe-row)))))

(defn- setup-goto-line-bar! [^GridPane goto-line-bar view-node]
  (b/bind-presence! goto-line-bar (b/= :goto-line bar-ui-type-property))
  (ui/with-controls goto-line-bar [^TextField line-field ^Button go-button]
    (ui/bind-keys! goto-line-bar {KeyCode/ENTER :goto-entered-line})
    (ui/bind-action! go-button :goto-entered-line)
    (ui/observe (.textProperty line-field)
                (fn [_ _ line-field-text]
                  (ui/refresh-bound-action-enabled! go-button)
                  (let [maybe-row (try-parse-goto-line-text view-node line-field-text)
                        error-message (when-not (number? maybe-row) maybe-row)]
                    (assert (or (nil? error-message) (string? error-message)))
                    (ui/tooltip! line-field error-message)
                    (if (some? error-message)
                      (ui/add-style! line-field "field-error")
                      (ui/remove-style! line-field "field-error"))))))
  (doto goto-line-bar
    (ui/context! :goto-line-bar {:goto-line-bar goto-line-bar :view-node view-node} nil)
    (.setMaxWidth Double/MAX_VALUE)
    (GridPane/setConstraints 0 1)))

(defn- dispose-goto-line-bar! [^GridPane goto-line-bar]
  (b/unbind! (.visibleProperty goto-line-bar)))

(handler/defhandler :goto-line :code-view-tools
  (run [goto-line-bar]
       (set-bar-ui-type! :goto-line)
       (ui/with-controls goto-line-bar [^TextField line-field]
         (.requestFocus line-field)
         (.selectAll line-field))))

(handler/defhandler :goto-entered-line :goto-line-bar
  (enabled? [goto-line-bar view-node evaluation-context]
            (some? (try-parse-goto-line-bar-row view-node goto-line-bar evaluation-context)))
  (run [goto-line-bar view-node]
       (when-some [line-number (try-parse-goto-line-bar-row view-node goto-line-bar)]
         (let [cursor-range (data/Cursor->CursorRange (data/->Cursor line-number 0))]
           (set-properties! view-node :navigation
                            (data/select-and-frame (get-property view-node :lines)
                                                   (get-property view-node :layout)
                                                   cursor-range)))
         (set-bar-ui-type! :hidden)
         ;; Close bar on next tick so the code view will not insert a newline
         ;; if the bar was dismissed by pressing the Enter key.
         (ui/run-later
           (focus-code-editor! view-node)))))

;; -----------------------------------------------------------------------------
;; Find & Replace
;; -----------------------------------------------------------------------------

(defn- setup-find-bar! [^GridPane find-bar view-node]
  (doto find-bar
    (b/bind-presence! (b/= :find bar-ui-type-property))
    (ui/context! :code-view-find-bar {:find-bar find-bar :view-node view-node} nil)
    (.setMaxWidth Double/MAX_VALUE)
    (GridPane/setConstraints 0 1))
  (ui/with-controls find-bar [^CheckBox whole-word ^CheckBox case-sensitive ^CheckBox wrap ^TextField term ^Button next ^Button prev]
    (b/bind-bidirectional! (.textProperty term) find-term-property)
    (b/bind-bidirectional! (.selectedProperty whole-word) find-whole-word-property)
    (b/bind-bidirectional! (.selectedProperty case-sensitive) find-case-sensitive-property)
    (b/bind-bidirectional! (.selectedProperty wrap) find-wrap-property)
    (ui/bind-key-commands! find-bar {"Enter" :find-next
                                     "Shift+Enter" :find-prev})
    (ui/bind-action! next :find-next)
    (ui/bind-action! prev :find-prev))
  find-bar)

(defn- dispose-find-bar! [^GridPane find-bar]
  (b/unbind! (.visibleProperty find-bar))
  (ui/with-controls find-bar [^CheckBox whole-word ^CheckBox case-sensitive ^CheckBox wrap ^TextField term]
    (b/unbind-bidirectional! (.textProperty term) find-term-property)
    (b/unbind-bidirectional! (.selectedProperty whole-word) find-whole-word-property)
    (b/unbind-bidirectional! (.selectedProperty case-sensitive) find-case-sensitive-property)
    (b/unbind-bidirectional! (.selectedProperty wrap) find-wrap-property)))

(defn- setup-replace-bar! [^GridPane replace-bar view-node]
  (doto replace-bar
    (b/bind-presence! (b/= :replace bar-ui-type-property))
    (ui/context! :code-view-replace-bar {:replace-bar replace-bar :view-node view-node} nil)
    (.setMaxWidth Double/MAX_VALUE)
    (GridPane/setConstraints 0 1))
  (ui/with-controls replace-bar [^CheckBox whole-word ^CheckBox case-sensitive ^CheckBox wrap ^TextField term ^TextField replacement ^Button next ^Button replace ^Button replace-all]
    (b/bind-bidirectional! (.textProperty term) find-term-property)
    (b/bind-bidirectional! (.textProperty replacement) find-replacement-property)
    (b/bind-bidirectional! (.selectedProperty whole-word) find-whole-word-property)
    (b/bind-bidirectional! (.selectedProperty case-sensitive) find-case-sensitive-property)
    (b/bind-bidirectional! (.selectedProperty wrap) find-wrap-property)
    (ui/bind-action! next :find-next)
    (ui/bind-action! replace :replace-next)
    (ui/bind-keys! replace-bar {KeyCode/ENTER :replace-next})
    (ui/bind-action! replace-all :replace-all))
  replace-bar)

(defn- dispose-replace-bar! [^GridPane replace-bar]
  (b/unbind! (.visibleProperty replace-bar))
  (ui/with-controls replace-bar [^CheckBox whole-word ^CheckBox case-sensitive ^CheckBox wrap ^TextField term ^TextField replacement]
    (b/unbind-bidirectional! (.textProperty term) find-term-property)
    (b/unbind-bidirectional! (.textProperty replacement) find-replacement-property)
    (b/unbind-bidirectional! (.selectedProperty whole-word) find-whole-word-property)
    (b/unbind-bidirectional! (.selectedProperty case-sensitive) find-case-sensitive-property)
    (b/unbind-bidirectional! (.selectedProperty wrap) find-wrap-property)))

(defn- focus-term-field! [^Parent bar]
  (ui/with-controls bar [^TextField term]
    (.requestFocus term)
    (.selectAll term)))

(defn- set-find-term! [^String term-text]
  (.setValue find-term-property (or term-text "")))

(defn non-empty-single-selection-text [view-node]
  (when-some [single-cursor-range (util/only (get-property view-node :cursor-ranges))]
    (when-not (data/cursor-range-empty? single-cursor-range)
      (data/cursor-range-text (get-property view-node :lines) single-cursor-range))))

(defn- find-next! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node :selection
                   (data/find-next (get-property view-node :lines)
                                   (get-property view-node :cursor-ranges)
                                   (get-property view-node :layout)
                                   (split-lines (.getValue find-term-property))
                                   (.getValue find-case-sensitive-property)
                                   (.getValue find-whole-word-property)
                                   (.getValue find-wrap-property))))

(defn- find-prev! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node :selection
                   (data/find-prev (get-property view-node :lines)
                                   (get-property view-node :cursor-ranges)
                                   (get-property view-node :layout)
                                   (split-lines (.getValue find-term-property))
                                   (.getValue find-case-sensitive-property)
                                   (.getValue find-whole-word-property)
                                   (.getValue find-wrap-property))))

(defn- replace-next! [view-node]
  (hide-suggestions! view-node)
  (set-properties! view-node nil
                   (data/replace-next (get-property view-node :lines)
                                      (get-property view-node :cursor-ranges)
                                      (get-property view-node :regions)
                                      (get-property view-node :layout)
                                      (split-lines (.getValue find-term-property))
                                      (split-lines (.getValue find-replacement-property))
                                      (.getValue find-case-sensitive-property)
                                      (.getValue find-whole-word-property)
                                      (.getValue find-wrap-property))))

(defn- replace-all! [view-node]
  (hide-suggestions! view-node)
  (let [^String find-term (.getValue find-term-property)]
    (when (pos? (.length find-term))
      (set-properties! view-node nil
                       (data/replace-all (get-property view-node :lines)
                                         (get-property view-node :regions)
                                         (get-property view-node :layout)
                                         (split-lines find-term)
                                         (split-lines (.getValue find-replacement-property))
                                         (.getValue find-case-sensitive-property)
                                         (.getValue find-whole-word-property))))))

(handler/defhandler :find-text :code-view
  (run [find-bar view-node]
       (when-some [selected-text (non-empty-single-selection-text view-node)]
         (set-find-term! selected-text))
       (set-bar-ui-type! :find)
       (focus-term-field! find-bar)))

(handler/defhandler :replace-text :code-view
  (run [replace-bar view-node]
       (when-some [selected-text (non-empty-single-selection-text view-node)]
         (set-find-term! selected-text))
       (set-bar-ui-type! :replace)
       (focus-term-field! replace-bar)))

(handler/defhandler :find-text :code-view-tools ;; In practice, from find / replace and go to line bars.
  (run [find-bar]
       (set-bar-ui-type! :find)
       (focus-term-field! find-bar)))

(handler/defhandler :replace-text :code-view-tools
  (run [replace-bar]
       (set-bar-ui-type! :replace)
       (focus-term-field! replace-bar)))

(handler/defhandler :escape :code-view-tools
  (run [find-bar replace-bar view-node]
       (cond
         (in-tab-trigger? view-node)
         (exit-tab-trigger! view-node)

         (suggestions-visible? view-node)
         (hide-suggestions! view-node)

         (bar-ui-visible?)
         (do (set-bar-ui-type! :hidden)
             (focus-code-editor! view-node))

         :else
         (set-properties! view-node :selection
                          (data/escape (get-property view-node :cursor-ranges))))))

(handler/defhandler :find-next :code-view-find-bar
  (run [view-node] (find-next! view-node)))

(handler/defhandler :find-next :code-view-replace-bar
  (run [view-node] (find-next! view-node)))

(handler/defhandler :find-next :code-view
  (run [view-node] (find-next! view-node)))

(handler/defhandler :find-prev :code-view-find-bar
  (run [view-node] (find-prev! view-node)))

(handler/defhandler :find-prev :code-view-replace-bar
  (run [view-node] (find-prev! view-node)))

(handler/defhandler :find-prev :code-view
  (run [view-node] (find-prev! view-node)))

(handler/defhandler :replace-next :code-view-replace-bar
  (run [view-node] (replace-next! view-node)))

(handler/defhandler :replace-next :code-view
  (run [view-node] (replace-next! view-node)))

(handler/defhandler :replace-all :code-view-replace-bar
  (run [view-node] (replace-all! view-node)))

;; -----------------------------------------------------------------------------

(handler/register-menu! ::menubar :editor.app-view/edit-end
  [{:command :find-text :label "Find..."}
   {:command :find-next :label "Find Next"}
   {:command :find-prev :label "Find Previous"}
   {:label :separator}
   {:command :replace-text :label "Replace..."}
   {:command :replace-next :label "Replace Next"}
   {:label :separator}
   {:command :toggle-comment :label "Toggle Comment"}
   {:command :reindent :label "Reindent Lines"}

   {:label "Convert Indentation"
    :children [{:label "To Tabs"
                :command :convert-indentation
                :user-data :tabs}
               {:label "To Two Spaces"
                :command :convert-indentation
                :user-data :two-spaces}
               {:label "To Four Spaces"
                :command :convert-indentation
                :user-data :four-spaces}]}

   {:label :separator}
   {:command :sort-lines :label "Sort Lines"}
   {:command :sort-lines-case-sensitive :label "Sort Lines (Case Sensitive)"}
   {:label :separator}
   {:command :select-next-occurrence :label "Select Next Occurrence"}
   {:command :split-selection-into-lines :label "Split Selection Into Lines"}
   {:label :separator}
   {:command :goto-definition :label "Go to Definition"}
   {:command :find-references :label "Find References"}
   {:label :separator}
   {:command :toggle-breakpoint :label "Toggle Breakpoint"}
   {:command :edit-breakpoint :label "Edit Breakpoint"}])

(handler/register-menu! ::menubar :editor.app-view/view-end
  [{:command :toggle-minimap :label "Minimap" :check true}
   {:command :toggle-indentation-guides :label "Indentation Guides" :check true}
   {:command :toggle-visible-whitespace :label "Visible Whitespace" :check true}
   {:label :separator}
   {:command :zoom-in :label "Increase Font Size"}
   {:command :zoom-out :label "Decrease Font Size"}
   {:command :reset-zoom :label "Reset Font Size"}
   {:label :separator}
   {:command :goto-line :label "Go to Line..."}])

;; -----------------------------------------------------------------------------

(defn- setup-view! [resource-node view-node app-view lsp]
  ;; Grab the unmodified lines or io error before opening the
  ;; file. Otherwise this will happen on the first edit. If a
  ;; background process has modified (or even deleted) the file
  ;; without the editor knowing, the "original" unmodified lines
  ;; reached after a series of undo's could be something else entirely
  ;; than what the user saw.
  (g/with-auto-evaluation-context ec
    (r/ensure-loaded! resource-node ec)
    (let [glyph-metrics (g/node-value view-node :glyph-metrics ec)
          tab-spaces (g/node-value view-node :tab-spaces ec)
          tab-stops (data/tab-stops glyph-metrics tab-spaces)
          lines (g/node-value resource-node :lines ec)
          document-width (data/max-line-width glyph-metrics tab-stops lines)
          resource (g/node-value resource-node :resource ec)]
      (when (resource/file-resource? resource)
        (lsp/open-view! lsp view-node resource lines))
      (g/transact
        (concat
          (g/set-property view-node :document-width document-width)
          (g/connect resource-node :completions view-node :completions)
          (g/connect resource-node :cursor-ranges view-node :cursor-ranges)
          (g/connect resource-node :indent-type view-node :indent-type)
          (g/connect resource-node :invalidated-rows view-node :invalidated-rows)
          (g/connect resource-node :lines view-node :lines)
          (g/connect resource-node :regions view-node :regions)
          (g/connect app-view :debugger-execution-locations view-node :debugger-execution-locations)))
      view-node)))

(defn- cursor-opacity
  ^double [^double elapsed-time-at-last-action ^double elapsed-time]
  (if (< ^double (mod (- elapsed-time elapsed-time-at-last-action) 1.0) 0.5) 1.0 0.0))

(defn- draw-fps-counters! [^GraphicsContext gc ^double fps]
  (let [canvas (.getCanvas gc)
        margin 14.0
        width 83.0
        height 24.0
        top margin
        right (- (.getWidth canvas) margin)
        left (- right width)]
    (.setFill gc Color/DARKSLATEGRAY)
    (.fillRect gc left top width height)
    (.setFill gc Color/WHITE)
    (.fillText gc (format "%.3f fps" fps) (- right 5.0) (+ top 16.0))))

(defn repaint-view! [view-node elapsed-time {:keys [cursor-visible] :as _opts}]
  (assert (boolean? cursor-visible))

  ;; Since the elapsed time updates at 60 fps, we store it as user-data to avoid transaction churn.
  (g/user-data! view-node :elapsed-time elapsed-time)

  ;; Perform necessary property updates in preparation for repaint.
  (g/with-auto-evaluation-context evaluation-context
    (let [tick-props (data/tick (g/node-value view-node :lines evaluation-context)
                                (g/node-value view-node :layout evaluation-context)
                                (g/node-value view-node :gesture-start evaluation-context))
          props (if-not cursor-visible
                  tick-props
                  (let [elapsed-time-at-last-action (g/node-value view-node :elapsed-time-at-last-action evaluation-context)
                        old-cursor-opacity (g/node-value view-node :cursor-opacity evaluation-context)
                        new-cursor-opacity (cursor-opacity elapsed-time-at-last-action elapsed-time)]
                    (cond-> tick-props
                            (not= old-cursor-opacity new-cursor-opacity)
                            (assoc :cursor-opacity new-cursor-opacity))))]
      (set-properties! view-node nil props)))

  ;; Repaint the view.
  (let [prev-canvas-repaint-info (g/user-data view-node :canvas-repaint-info)
        prev-cursor-repaint-info (g/user-data view-node :cursor-repaint-info)
        existing-completion-popup-renderer (g/user-data view-node :completion-popup-renderer)

        [resource-node canvas-repaint-info cursor-repaint-info completion-state]
        (g/with-auto-evaluation-context evaluation-context
          [(g/node-value view-node :resource-node evaluation-context)
           (g/node-value view-node :canvas-repaint-info evaluation-context)
           (g/node-value view-node :cursor-repaint-info evaluation-context)
           (g/node-value view-node :completion-state evaluation-context)])]

    ;; Repaint canvas if needed.
    (when-not (identical? prev-canvas-repaint-info canvas-repaint-info)
      (g/user-data! view-node :canvas-repaint-info canvas-repaint-info)
      (let [{:keys [grammar layout lines]} canvas-repaint-info
            syntax-info (if (nil? grammar)
                          []
                          (if-some [prev-syntax-info (g/user-data resource-node :syntax-info)]
                            (let [invalidated-syntax-info (if-some [invalidated-row (invalidated-row (:invalidated-rows prev-canvas-repaint-info) (:invalidated-rows canvas-repaint-info))]
                                                            (data/invalidate-syntax-info prev-syntax-info invalidated-row (count lines))
                                                            prev-syntax-info)]
                              (data/highlight-visible-syntax lines invalidated-syntax-info layout grammar))
                            (data/highlight-visible-syntax lines [] layout grammar)))]
        (g/user-data! resource-node :syntax-info syntax-info)
        (repaint-canvas! canvas-repaint-info syntax-info)))

    (when (:enabled completion-state)
      (let [renderer (or existing-completion-popup-renderer
                         (g/user-data!
                           view-node
                           :completion-popup-renderer
                           (fx/create-renderer
                             :error-handler error-reporting/report-exception!
                             :middleware (comp
                                           fxui/wrap-dedupe-desc
                                           (fx/wrap-map-desc #'completion-popup-view))
                             :opts {:fx.opt/map-event-handler #(handle-completion-popup-event view-node %)})))
            ^Canvas canvas (:canvas canvas-repaint-info)]
        (g/with-auto-evaluation-context evaluation-context
          (renderer {:completion-state completion-state
                     :canvas-repaint-info canvas-repaint-info
                     :visible-completion-ranges (g/node-value view-node :visible-completion-ranges evaluation-context)
                     :query (:query (g/node-value view-node :completion-context evaluation-context))
                     :font (g/node-value view-node :font evaluation-context)
                     :font-name (g/node-value view-node :font-name evaluation-context)
                     :font-size (g/node-value view-node :font-size evaluation-context)
                     :window-x (some-> (.getScene canvas) .getWindow .getX)
                     :window-y (some-> (.getScene canvas) .getWindow .getY)
                     :screen-bounds (mapv #(.getVisualBounds ^Screen %) (Screen/getScreens))}))))

    ;; Repaint cursors if needed.
    (when-not (identical? prev-cursor-repaint-info cursor-repaint-info)
      (g/user-data! view-node :cursor-repaint-info cursor-repaint-info)
      (repaint-cursors! cursor-repaint-info))

    ;; Draw average fps indicator if enabled.
    (when-some [^PerformanceTracker performance-tracker @*performance-tracker]
      (let [{:keys [^Canvas canvas ^long repaint-trigger]} canvas-repaint-info]
        (g/set-property! view-node :repaint-trigger (unchecked-inc repaint-trigger))
        (draw-fps-counters! (.getGraphicsContext2D canvas) (.getInstantFPS performance-tracker))
        (when (= 0 (mod repaint-trigger 10))
          (.resetAverageFPS performance-tracker))))))

(defn- make-execution-marker-arrow
  ([x y w h]
   (make-execution-marker-arrow x y w h 0.5 0.4))
  ([x y w h w-prop h-prop]
   (let [x ^long x
         y ^long y
         w ^long w
         h ^long h
         w-body (* ^double w-prop w)
         h-body (* ^double h-prop h)
         ;; x coords used, left to right
         x0 x
         x1 (+ x0 w-body)
         x2 (+ x0 w)
         ;; y coords used, top to bottom
         y0 y
         y1 (+ y (* 0.5 (- h h-body)))
         y2 (+ y (* 0.5 h))
         y3 (+ y (- h (* 0.5 (- h h-body))))
         y4 (+ y h)]
     {:xs (double-array [x0 x1 x1 x2 x1 x1 x0])
      :ys (double-array [y1 y1 y0 y2 y4 y3 y3])
      :n  7})))

(deftype CodeEditorGutterView []
  GutterView

  (gutter-metrics [this lines regions glyph-metrics]
    (let [gutter-margin (data/line-height glyph-metrics)]
      (data/gutter-metrics glyph-metrics gutter-margin (count lines))))

  (draw-gutter! [this gc gutter-rect layout font color-scheme lines regions visible-cursors]
    (let [^GraphicsContext gc gc
          ^Rect gutter-rect gutter-rect
          ^LayoutInfo layout layout
          glyph-metrics (.glyph layout)
          ^double line-height (data/line-height glyph-metrics)
          gutter-foreground-color (color-lookup color-scheme "editor.gutter.foreground")
          gutter-background-color (color-lookup color-scheme "editor.gutter.background")
          gutter-shadow-color (color-lookup color-scheme "editor.gutter.shadow")
          gutter-breakpoint-color (color-lookup color-scheme "editor.gutter.breakpoint")
          gutter-cursor-line-background-color (color-lookup color-scheme "editor.gutter.cursor.line.background")
          gutter-execution-marker-current-color (color-lookup color-scheme "editor.gutter.execution-marker.current")
          gutter-execution-marker-frame-color (color-lookup color-scheme "editor.gutter.execution-marker.frame")]

      ;; Draw gutter background and shadow when scrolled horizontally.
      (when (neg? (.scroll-x layout))
        (.setFill gc gutter-background-color)
        (.fillRect gc (.x gutter-rect) (.y gutter-rect) (.w gutter-rect) (.h gutter-rect))
        (.setFill gc gutter-shadow-color)
        (.fillRect gc (+ (.x gutter-rect) (.w gutter-rect)) 0.0 8.0 (.h gutter-rect)))

      ;; Highlight lines with cursors in gutter.
      (.setFill gc gutter-cursor-line-background-color)
      (let [highlight-width (- (+ (.x gutter-rect) (.w gutter-rect)) (/ line-height 2.0))
            highlight-height (dec line-height)]
        (doseq [^Cursor cursor visible-cursors]
          (let [y (+ (data/row->y layout (.row cursor)) 0.5)]
            (.fillRect gc 0 y highlight-width highlight-height))))

      ;; Draw line numbers and markers in gutter.
      (.setFont gc font)
      (.setTextAlign gc TextAlignment/RIGHT)
      (let [^Rect line-numbers-rect (.line-numbers layout)
            ^double ascent (data/ascent glyph-metrics)
            drawn-line-count (.drawn-line-count layout)
            dropped-line-count (.dropped-line-count layout)
            source-line-count (count lines)
            indicator-offset 3.0
            indicator-diameter (- line-height indicator-offset indicator-offset)
            breakpoint-row->condition (into {}
                                            (comp
                                              (filter data/breakpoint-region?)
                                              (map (juxt data/breakpoint-row #(:condition % true))))
                                            regions)
            execution-markers-by-type (group-by :location-type (filter data/execution-marker? regions))
            execution-marker-current-rows (data/cursor-ranges->start-rows lines (:current-line execution-markers-by-type))
            execution-marker-frame-rows (data/cursor-ranges->start-rows lines (:current-frame execution-markers-by-type))]
        (loop [drawn-line-index 0
               source-line-index dropped-line-count]
          (when (and (< drawn-line-index drawn-line-count)
                     (< source-line-index source-line-count))
            (let [y (data/row->y layout source-line-index)
                  condition (breakpoint-row->condition source-line-index)]
              (when condition
                (.setFill gc gutter-breakpoint-color)
                (.fillOval gc
                           (+ (.x line-numbers-rect) (.w line-numbers-rect) indicator-offset)
                           (+ y indicator-offset) indicator-diameter indicator-diameter)
                (when (string? condition)
                  (doto gc
                    (.save)
                    (.setFill gutter-background-color)
                    (.translate
                      ;; align to the center of the breakpoint indicator
                      (+ (.x line-numbers-rect)
                         (.w line-numbers-rect)
                         indicator-offset
                         (* indicator-diameter 0.5))
                      (+ y indicator-offset (* indicator-diameter 0.5)))
                    ;; magic scaling constant to make the icon fit
                    (.scale (/ indicator-diameter 180.0) (/ indicator-diameter 180.0))
                    (.beginPath)
                    ;; The following SVG path is taken from here:
                    ;; https://uxwing.com/question-mark-icon/
                    ;; License: All icons are free to use any personal and commercial projects without any attribution or credit
                    ;; Then, the path was edited on this svg editor site:
                    ;; https://yqnn.github.io/svg-path-editor/
                    ;; I translated it up and to the left so the center of the question mark is on 0,0
                    (.appendSVGPath "M 15.68 24.96 H -15.63 V 21.84 C -15.63 16.52 -15.04 12.19 -13.83 8.87 C -12.62 5.52 -10.82 2.51 -8.43 -0.24 C -6.04 -3 -0.67 -7.84 7.69 -14.76 C 12.14 -18.39 14.36 -21.71 14.36 -24.72 C 14.36 -27.76 13.46 -30.09 11.69 -31.78 C 9.89 -33.44 7.19 -34.28 3.56 -34.28 C -0.35 -34.28 -3.56 -32.99 -6.12 -30.4 C -8.68 -27.84 -10.31 -23.31 -11.02 -16.9 L -43 -20.87 C -41.9 -32.63 -37.63 -42.08 -30.2 -49.26 C -22.75 -56.43 -11.32 -60 4.06 -60 C 16.04 -60 25.69 -57.5 33.06 -52.52 C 43.05 -45.74 48.06 -36.74 48.06 -25.48 C 48.06 -20.81 46.77 -16.28 44.18 -11.95 C 41.62 -7.62 36.33 -2.3 28.37 3.94 C 22.83 8.36 19.31 11.87 17.85 14.55 C 16.42 17.19 15.68 20.68 15.68 24.96 L 15.68 24.96 Z M -16.72 33.29 H 16.84 V 62.89 H -16.72 V 33.29 L -16.72 33.29 Z")
                    (.fill)
                    (.restore))))
              (when (contains? execution-marker-current-rows source-line-index)
                (let [x (+ (.x line-numbers-rect) (.w line-numbers-rect) 4.0)
                      y (+ y 4.0)
                      w (- line-height 8.0)
                      h (- line-height 8.0)
                      {:keys [xs ys n]} (make-execution-marker-arrow x y w h)]
                  (.setFill gc gutter-execution-marker-current-color)
                  (.fillPolygon gc xs ys n)))
              (when (contains? execution-marker-frame-rows source-line-index)
                (let [x (+ (.x line-numbers-rect) (.w line-numbers-rect) 4.0)
                      y (+ y 4.0)
                      w (- line-height 8.0)
                      h (- line-height 8.0)
                      {:keys [xs ys n]} (make-execution-marker-arrow x y w h)]
                  (.setFill gc gutter-execution-marker-frame-color)
                  (.fillPolygon gc xs ys n)))
              (.setFill gc gutter-foreground-color)
              (.fillText gc (str (inc source-line-index))
                         (+ (.x line-numbers-rect) (.w line-numbers-rect))
                         (+ ascent y))
              (recur (inc drawn-line-index)
                     (inc source-line-index)))))))))

(defn make-property-change-setter
  (^ChangeListener [node-id prop-kw]
   (make-property-change-setter node-id prop-kw identity))
  (^ChangeListener [node-id prop-kw observable-value->node-value]
   (assert (integer? node-id))
   (assert (keyword? prop-kw))
   (reify ChangeListener
     (changed [_this _observable _old new]
       (g/set-property! node-id prop-kw (observable-value->node-value new))))))

(defn make-focus-change-listener
  ^ChangeListener [view-node parent canvas]
  (assert (integer? view-node))
  (assert (instance? Parent parent))
  (assert (instance? Canvas canvas))
  (reify ChangeListener
    (changed [_ _ _ focus-owner]
      (g/set-property! view-node :focus-state
                       (cond
                         (= canvas focus-owner)
                         :input-focused

                         (some? (ui/closest-node-where (partial = parent) focus-owner))
                         :semi-focused

                         :else
                         :not-focused)))))

;; JavaFX generally reports wrong key-typed events when typing tilde on Swedish
;; keyboard layout, which is a problem when writing Lua because it uses ~ for negation,
;; so typing "AltGr ` =" inserts "~¨=" instead of "~="
;; Original JavaFX issue: https://bugs.openjdk.java.net/browse/JDK-8183521
;; See also: https://github.com/javafxports/openjdk-jfx/issues/358

(defn- wrap-disallow-diaeresis-after-tilde
  ^EventHandler [^EventHandler handler]
  (let [last-event-volatile (volatile! nil)]
    (ui/event-handler event
      (let [^KeyEvent new-event event
            ^KeyEvent prev-event @last-event-volatile]
        (vreset! last-event-volatile event)
        (when-not (and (some? prev-event)
                       (= "~" (.getCharacter prev-event))
                       (= "¨" (.getCharacter new-event)))
          (.handle handler event))))))

(defn handle-input-method-changed! [view-node ^InputMethodEvent e]
  (let [x (.getCommitted e)]
    (when-not (.isEmpty x)
      (insert-text! view-node ({"≃" "~="} x x)))))

(defn- setup-input-method-requests! [^Canvas canvas view-node]
  (when (eutil/is-linux?)
    (doto canvas
      (.setInputMethodRequests
        (reify InputMethodRequests
          (getTextLocation [_this _offset] Point2D/ZERO)
          (getLocationOffset [_this _x _y] 0)
          (cancelLatestCommittedText [_this] "")
          (getSelectedText [_this] "")))
      (.setOnInputMethodTextChanged
        (ui/event-handler e
          (handle-input-method-changed! view-node e))))))

(defmulti hoverable-region-view :type)

(defmethod hoverable-region-view :diagnostic [{:keys [messages]}]
  {:fx/type fx.v-box/lifecycle
   :children (into []
                   (comp
                     (map (fn [message]
                            {:fx/type fx.label/lifecycle
                             :padding 5
                             :wrap-text true
                             :text message}))
                     (interpose {:fx/type fx.region/lifecycle
                                 :style-class "hover-separator"}))
                   messages)})

(defn- hover-view [^Canvas canvas {:keys [hovered-element layout lines]}]
  (let [^Rect r (->> hovered-element
                     :region
                     (data/adjust-cursor-range lines)
                     (data/cursor-range-rects layout lines)
                     first)
        anchor (.localToScreen canvas (.-x r) (.-y r))]
    {:fx/type fxui/with-popup
     :desc {:fx/type fxui/ext-value :value canvas}
     :showing true
     :anchor-x (.getX anchor)
     :anchor-y (.getY anchor)
     :anchor-location :window-bottom-left
     :auto-hide true
     :auto-fix true
     :hide-on-escape true
     :consume-auto-hiding-events true
     :content [{:fx/type fx.v-box/lifecycle
                :stylesheets [(str (io/resource "dialogs.css"))]
                :style-class "hover-popup"
                :max-width 300
                :children [(assoc (:region hovered-element)
                             :fx/type hoverable-region-view
                             :v-box/vgrow :always)]}]}))

(defn- create-hover! [view-node canvas ^Tab tab]
  (let [state (atom nil)
        refresh (fn refresh []
                  (g/with-auto-evaluation-context evaluation-context
                    (let [hovered-element (g/node-value view-node :hovered-element evaluation-context)]
                      (if (and (= :region (:type hovered-element))
                               (:hoverable (:region hovered-element)))
                        (reset! state {:hovered-element hovered-element
                                       :layout (g/node-value view-node :layout evaluation-context)
                                       :lines (g/node-value view-node :lines evaluation-context)})
                        (reset! state nil)))))
        timer (ui/->timer 10 "hover-code-editor-timer" (fn [_ _ _]
                                                         (when (and (.isSelected tab) (not (ui/ui-disabled?)))
                                                           (refresh))))]
    (fx/mount-renderer state (fx/create-renderer
                               :error-handler error-reporting/report-exception!
                               :middleware (comp
                                             fxui/wrap-dedupe-desc
                                             (fx/wrap-map-desc #(hover-view canvas %)))))
    (ui/timer-start! timer)
    (fn dispose []
      (ui/timer-stop! timer)
      (reset! state nil))))

(defn- consume-breakpoint-popup-events [^Event e]
  (when-not (and (instance? KeyEvent e)
                 (= KeyEvent/KEY_PRESSED (.getEventType e))
                 (= KeyCode/ESCAPE (.getCode ^KeyEvent e)))
    (.consume e)))

(defn- breakpoint-editor-view [^Canvas canvas {:keys [edited-breakpoint gutter-metrics layout]}]
  (let [[^double gutter-width ^double gutter-margin] gutter-metrics
        spacing 4
        padding 8
        anchor (.localToScreen canvas
                               (- gutter-width
                                  (* gutter-margin 0.5)
                                  12) ;; shadow offset
                               (+ (* ^double (data/line-height (:glyph layout)) 0.5)
                                  (data/row->y layout (data/breakpoint-row edited-breakpoint))))]
    {:fx/type fxui/with-popup
     :desc {:fx/type fxui/ext-value :value (.getWindow (.getScene canvas))}
     :showing true
     :anchor-x (.getX anchor)
     :anchor-y (.getY anchor)
     :anchor-location :window-top-left
     :auto-hide true
     :auto-fix true
     :hide-on-escape true
     :on-auto-hide {:event :cancel}
     :consume-auto-hiding-events true
     :event-handler consume-breakpoint-popup-events
     :content
     [{:fx/type fx.v-box/lifecycle
       :stylesheets [(str (io/resource "editor.css"))]
       :children
       [{:fx/type fx.v-box/lifecycle
         :style-class "breakpoint-editor"
         :fill-width false
         :spacing -1
         :children
         [{:fx/type fx.region/lifecycle
           :view-order -1
           :style-class "breakpoint-editor-arrow"
           :min-width 10
           :min-height 10}
          {:fx/type fx.stack-pane/lifecycle
           :children
           [{:fx/type fx.region/lifecycle
             :style-class "breakpoint-editor-background"}
            {:fx/type fx.v-box/lifecycle
             :style-class "breakpoint-editor-content"
             :spacing padding
             :children
             [{:fx/type fx.label/lifecycle
               :style-class ["label" "breakpoint-editor-label" "breakpoint-editor-header"]
               :text (format "Breakpoint on line %d" (data/CursorRange->line-number edited-breakpoint))}
              {:fx/type fx.h-box/lifecycle
               :spacing spacing
               :alignment :baseline-left
               :children
               [{:fx/type fx.label/lifecycle
                 :style-class ["label" "breakpoint-editor-label"]
                 :text "Condition"}
                {:fx/type fxui/text-field
                 :h-box/hgrow :always
                 :style-class ["text-field" "breakpoint-editor-label"]
                 :prompt-text "e.g. i == 1"
                 :text (:condition edited-breakpoint "")
                 :on-text-changed {:event :edit}
                 :on-action {:event :apply}}]}
              {:fx/type fx.h-box/lifecycle
               :spacing spacing
               :alignment :center-right
               :children
               [{:fx/type fx.button/lifecycle
                 :style-class ["button" "breakpoint-editor-button"]
                 :text "OK"
                 :on-action {:event :apply}}]}]}]}]}]}]}))

(defn- create-breakpoint-editor! [view-node canvas ^Tab tab]
  (let [state (atom nil)
        timer (ui/->timer
                10
                "breakpoint-code-editor-timer"
                (fn [_ _ _]
                  (when (and (.isSelected tab) (not (ui/ui-disabled?)))
                    (g/with-auto-evaluation-context evaluation-context
                      (reset! state
                        (when-let [edited-breakpoint (g/node-value view-node :edited-breakpoint evaluation-context)]
                          {:edited-breakpoint edited-breakpoint
                           :gutter-metrics (g/node-value view-node :gutter-metrics evaluation-context)
                           :layout (g/node-value view-node :layout evaluation-context)}))))))]
    (fx/mount-renderer
      state
      (fx/create-renderer
        :error-handler error-reporting/report-exception!
        :opts {:fx.opt/map-event-handler
               (fn [event]
                 (g/with-auto-evaluation-context evaluation-context
                   (when-let [edited-breakpoint (g/node-value view-node :edited-breakpoint evaluation-context)]
                     (set-properties!
                       view-node
                       nil
                       (case (:event event)
                         :edit
                         {:edited-breakpoint (assoc edited-breakpoint :condition (:fx/event event))}

                         :cancel
                         {:edited-breakpoint nil}

                         :apply
                         (assoc (data/ensure-breakpoint
                                  (g/node-value view-node :lines evaluation-context)
                                  (g/node-value view-node :regions evaluation-context)
                                  edited-breakpoint)
                           :edited-breakpoint nil))))))}
        :middleware (comp
                      fxui/wrap-dedupe-desc
                      (fx/wrap-map-desc #(breakpoint-editor-view canvas %)))))
    (ui/timer-start! timer)
    (fn dispose-breakpoint-editor! []
      (ui/timer-stop! timer)
      (reset! state nil))))

(defn- make-view! [graph parent resource-node opts]
  (let [{:keys [^Tab tab app-view grammar open-resource-fn project]} opts
        grid (GridPane.)
        canvas (Canvas.)
        canvas-pane (Pane. (into-array Node [canvas]))
        undo-grouping-info (pair :navigation (gensym))
        lsp (lsp/get-node-lsp resource-node)
        view-node (setup-view! resource-node
                               (g/make-node! graph CodeEditorView
                                             :canvas canvas
                                             :color-scheme code-color-scheme
                                             :font-size (.getValue font-size-property)
                                             :font-name  (.getValue font-name-property)
                                             :grammar grammar
                                             :gutter-view (->CodeEditorGutterView)
                                             :highlighted-find-term (.getValue highlighted-find-term-property)
                                             :line-height-factor 1.2
                                             :completion-state {:enabled true
                                                                :showing false
                                                                :show-doc false
                                                                :project project}
                                             :undo-grouping-info undo-grouping-info
                                             :visible-indentation-guides? (.getValue visible-indentation-guides-property)
                                             :visible-minimap? (.getValue visible-minimap-property)
                                             :visible-whitespace (boolean->visible-whitespace (.getValue visible-whitespace-property)))
                               app-view
                               lsp)
        goto-line-bar (setup-goto-line-bar! (ui/load-fxml "goto-line.fxml") view-node)
        find-bar (setup-find-bar! (ui/load-fxml "find.fxml") view-node)
        replace-bar (setup-replace-bar! (ui/load-fxml "replace.fxml") view-node)
        repainter (ui/->timer "repaint-code-editor-view"
                              (fn [_ elapsed-time _]
                                (when (and (.isSelected tab) (not (ui/ui-disabled?)))
                                  (repaint-view! view-node elapsed-time {:cursor-visible true}))))
        dispose-hover! (create-hover! view-node canvas tab)
        dispose-breakpoint-editor! (create-breakpoint-editor! view-node canvas tab)
        context-env {:clipboard (Clipboard/getSystemClipboard)
                     :goto-line-bar goto-line-bar
                     :find-bar find-bar
                     :replace-bar replace-bar
                     :view-node view-node
                     :open-resource-fn open-resource-fn}]

    ;; Canvas stretches to fit view, and updates properties in view node.
    (b/bind! (.widthProperty canvas) (.widthProperty canvas-pane))
    (b/bind! (.heightProperty canvas) (.heightProperty canvas-pane))
    (ui/observe (.widthProperty canvas) (fn [_ _ width] (g/set-property! view-node :canvas-width width)))
    (ui/observe (.heightProperty canvas) (fn [_ _ height] (g/set-property! view-node :canvas-height height)))

    ;; Configure canvas.
    (doto canvas
      (.setFocusTraversable true)
      (.setCursor javafx.scene.Cursor/TEXT)
      (setup-input-method-requests! view-node)
      (.addEventFilter KeyEvent/KEY_PRESSED (ui/event-handler event (handle-key-pressed! view-node event)))
      (.addEventHandler KeyEvent/KEY_TYPED (wrap-disallow-diaeresis-after-tilde
                                             (ui/event-handler event (handle-key-typed! view-node event))))
      (.addEventHandler MouseEvent/MOUSE_MOVED (ui/event-handler event (handle-mouse-moved! view-node event)))
      (.addEventHandler MouseEvent/MOUSE_PRESSED (ui/event-handler event (handle-mouse-pressed! view-node event)))
      (.addEventHandler MouseEvent/MOUSE_DRAGGED (ui/event-handler event (handle-mouse-moved! view-node event)))
      (.addEventHandler MouseEvent/MOUSE_RELEASED (ui/event-handler event (handle-mouse-released! view-node event)))
      (.addEventHandler MouseEvent/MOUSE_EXITED (ui/event-handler event (handle-mouse-exited! view-node event)))
      (.addEventHandler ScrollEvent/SCROLL (ui/event-handler event (handle-scroll! view-node event))))

    (ui/context! grid :code-view-tools context-env nil)

    (doto (.getColumnConstraints grid)
      (.add (doto (ColumnConstraints.)
              (.setHgrow Priority/ALWAYS))))

    (GridPane/setConstraints canvas-pane 0 0)
    (GridPane/setVgrow canvas-pane Priority/ALWAYS)

    (ui/children! grid [canvas-pane goto-line-bar find-bar replace-bar])
    (ui/children! parent [grid])
    (ui/fill-control grid)
    (ui/context! canvas :code-view context-env nil)

    ;; Steal input focus when our tab becomes selected.
    (ui/observe (.selectedProperty tab)
                (fn [_ _ became-selected?]
                  (when became-selected?
                    ;; Must run-later here since we're not part of the Scene when we observe the property change.
                    ;; Also note that we don't want to steal focus from the inactive tab pane, if present.
                    (ui/run-later
                      (when (identical? (.getTabPane tab)
                                        (g/node-value app-view :active-tab-pane))
                        (.requestFocus canvas))))))

    ;; Highlight occurrences of search term while find bar is open.
    (let [find-case-sensitive-setter (make-property-change-setter view-node :find-case-sensitive?)
          find-whole-word-setter (make-property-change-setter view-node :find-whole-word?)
          font-size-setter (make-property-change-setter view-node :font-size)
          highlighted-find-term-setter (make-property-change-setter view-node :highlighted-find-term)
          visible-indentation-guides-setter (make-property-change-setter view-node :visible-indentation-guides?)
          visible-minimap-setter (make-property-change-setter view-node :visible-minimap?)
          visible-whitespace-setter (make-property-change-setter view-node :visible-whitespace boolean->visible-whitespace)]
      (.addListener find-case-sensitive-property find-case-sensitive-setter)
      (.addListener find-whole-word-property find-whole-word-setter)
      (.addListener font-size-property font-size-setter)
      (.addListener highlighted-find-term-property highlighted-find-term-setter)
      (.addListener visible-indentation-guides-property visible-indentation-guides-setter)
      (.addListener visible-minimap-property visible-minimap-setter)
      (.addListener visible-whitespace-property visible-whitespace-setter)

      ;; Ensure the focus-state property reflects the current input focus state.
      (let [^Stage stage (g/node-value app-view :stage)
            ^Scene scene (.getScene stage)
            focus-owner-property (.focusOwnerProperty scene)
            focus-change-listener (make-focus-change-listener view-node grid canvas)]
        (.addListener focus-owner-property focus-change-listener)

        ;; Remove callbacks when our tab is closed.
        (ui/on-closed! tab (fn [_]
                             (lsp/close-view! lsp view-node)
                             (ui/kill-event-dispatch! canvas)
                             (ui/timer-stop! repainter)
                             (dispose-hover!)
                             (dispose-breakpoint-editor!)
                             (dispose-goto-line-bar! goto-line-bar)
                             (dispose-find-bar! find-bar)
                             (dispose-replace-bar! replace-bar)
                             (.removeListener find-case-sensitive-property find-case-sensitive-setter)
                             (.removeListener find-whole-word-property find-whole-word-setter)
                             (.removeListener font-size-property font-size-setter)
                             (.removeListener highlighted-find-term-property highlighted-find-term-setter)
                             (.removeListener visible-indentation-guides-property visible-indentation-guides-setter)
                             (.removeListener visible-minimap-property visible-minimap-setter)
                             (.removeListener visible-whitespace-property visible-whitespace-setter)
                             (.removeListener focus-owner-property focus-change-listener)))))

    ;; Start repaint timer.
    (ui/timer-start! repainter)
    ;; Initial draw
    (ui/run-later (repaint-view! view-node 0 {:cursor-visible true})
                  (ui/run-later (slog/smoke-log "code-view-visible")))
    view-node))

(defn- focus-view! [view-node opts]
  (.requestFocus ^Node (g/node-value view-node :canvas))
  (when-some [cursor-range (:cursor-range opts)]
    (set-properties! view-node :navigation
                     (data/select-and-frame (get-property view-node :lines)
                                            (get-property view-node :layout)
                                            cursor-range))))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
                                :id :code
                                :label "Code"
                                :make-view-fn (fn [graph parent resource-node opts] (make-view! graph parent resource-node opts))
                                :focus-fn (fn [view-node opts] (focus-view! view-node opts))
                                :text-selection-fn non-empty-single-selection-text))
