(ns newsroom.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [newsroom.markdown :as md]))

(deftest blocks
  (is (= "<h1>Title</h1><h2>Sub</h2><p>One line\ncontinues.</p><p>Two.</p>"
         (md/html "# Title\n## Sub\n\nOne line\ncontinues.\n\nTwo.")))
  (is (= "<ul><li>a</li><li>b</li></ul><ol><li>x</li><li>y</li></ol>"
         (md/html "- a\n* b\n\n1. x\n2. y")))
  (is (= "<blockquote><p>quoted\ntext</p></blockquote><hr>"
         (md/html "> quoted\n> text\n\n---")))
  (is (= "<pre><code>(+ 1 2)\n&lt;b&gt;</code></pre>"
         (md/html "```clojure\n(+ 1 2)\n<b>\n```"))))

(def ^:private tools
  (str "<div class=\"diagram-tools\">"
       "<button type=\"button\" data-diagram=\"in\" title=\"Zoom in\">+</button>"
       "<button type=\"button\" data-diagram=\"out\" title=\"Zoom out\">−</button>"
       "<button type=\"button\" data-diagram=\"reset\" title=\"Fit\">Fit</button>"
       "<button type=\"button\" data-diagram=\"expand\" title=\"Full screen\">⤢</button>"
       "<span class=\"diagram-hint\">drag to pan, ctrl + scroll to zoom</span>"
       "</div>"))

(deftest mermaid-diagrams
  (is (= (str "<figure class=\"diagram\">" tools
              "<div class=\"diagram-view\"><pre class=\"mermaid\">flowchart LR\n  A[&quot;Oil &amp; gas&quot;] --&gt; B</pre></div>"
              "</figure><p>After.</p>")
         (md/html "```mermaid\nflowchart LR\n  A[\"Oil & gas\"] --> B\n```\n\nAfter.")))
  (is (str/includes? (md/html "``` Mermaid \ngraph TD\n```") "<pre class=\"mermaid\">graph TD</pre>"))
  (let [h (md/html "```mermaid\nflowchart BT\n  %% caption: Read it <up>.\n  a --> b\n```")]
    (is (str/includes? h "<pre class=\"mermaid\">flowchart BT\n  a --&gt; b</pre>") "a caption isn't drawn")
    (is (str/includes? h "</div><figcaption>Read it &lt;up&gt;.</figcaption></figure>") "it is put under the diagram")))

(deftest inline
  (is (= "<p><strong>bold</strong> and <em>it</em> and <em>it2</em> and <code>c&lt;</code></p>"
         (md/html "**bold** and *it* and _it2_ and `c<`")))
  (is (= "<p>see <a href=\"https://e.com/a?x=1&amp;y=2\">the <em>story</em></a></p>"
         (md/html "see [the *story*](https://e.com/a?x=1&y=2)")))
  (is (= "<p>claim <a href=\"https://e.com/1\">[1]</a>, <a href=\"https://e.com/2\">[2]</a></p>"
         (md/html "claim [[1]](https://e.com/1), [[2]](https://e.com/2)")))
  (is (= "<p>&lt;script&gt;x&lt;/script&gt; a &amp; b</p>"
         (md/html "<script>x</script> a & b")))
  (is (= "<p>[unlinked]</p>" (md/html "[unlinked]")))
  (is (= "<p>snake_case_name stays</p>" (md/html "snake_case_name stays"))))

(deftest unsafe-links-are-not-links
  (is (= "<p>x</p>" (md/html "[x](javascript:alert(1))"))))
