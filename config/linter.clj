(disable-warning
  {:linter :suspicious-expression
   :for-macro 'clojure.core/or
   :if-inside-macroexpansion-of #{'clojure.core.async/alt!!}
   :within-depth 13})