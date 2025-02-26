(ns leiningen.core.classpath
  "Calculate project classpaths by resolving dependencies via Aether."
  (:require [cemerick.pomegranate.aether :as aether]
            [cemerick.pomegranate :as pomegranate]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [leiningen.core.user :as user]
            [leiningen.core.utils :as utils]
            [leiningen.core.pedantic :as pedantic])
  (:import (java.util.jar JarFile)
           (org.eclipse.aether.resolution DependencyResolutionException)))

(defn- warn [& args]
  ;; TODO: remove me once #1227 is merged
  (require 'leiningen.core.main)
  (apply (resolve 'leiningen.core.main/warn) args))

(def ^:private warn-once (memoize warn))

(defn ^:deprecated extract-native-deps [files native-path native-prefixes]
  (doseq [file files
          :let [native-prefix (get native-prefixes file "native/")
                jar (try (JarFile. file)
                      (catch Exception e
                        (throw (Exception. (format "Problem opening jar %s" file) e))))]
          entry (enumeration-seq (.entries jar))
          :when (.startsWith (.getName entry) native-prefix)]
    (let [f (io/file native-path (subs (.getName entry) (count native-prefix)))]
      (if (.isDirectory entry)
        (utils/mkdirs f)
        (do (utils/mkdirs (.getParentFile f))
            (io/copy (.getInputStream jar entry) f))))))

(defn extract-native-dep!
  "Extracts native content into the native path. Returns true if at least one
  file was extracted."
  [native-path file native-prefix]
  (let [native? (volatile! false)
        native-prefix (or native-prefix "native/")
        jar (try (JarFile. file)
                 (catch Exception e
                   (throw (Exception. (format "Problem opening jar %s" file) e))))]
    (doseq [entry (enumeration-seq (.entries jar))
            :when (.startsWith (.getName entry) native-prefix)]
      (vreset! native? true)
      (let [f (io/file native-path (subs (.getName entry) (count native-prefix)))]
        (if (.isDirectory entry)
          (utils/mkdirs f)
          (do (utils/mkdirs (.getParentFile f))
              (io/copy (.getInputStream jar entry) f)))))
    @native?))

(defn- stale-extract-native-deps
  "Extract native dependencies by comparing what has already been extracted to
  avoid redoing work. If stale files end up in some native path, new or old, the
  user will receive a warning -- we cannot delete files as the native path may
  be used for other things as well (or stuff generated earlier may be put in
  there)."
  [{old-deps :dependencies
    old-native-path :native-path} new-raw-deps relative-native-path native-path]
  (let [renamed-old-deps (utils/map-vals
                          old-deps
                          #(set/rename-keys % {:vsn :old-vsn
                                               :native-prefix :old-native-prefix
                                               :native? :old-native?}))
        renamed-new-raw-deps (utils/map-vals
                              new-raw-deps
                              #(set/rename-keys % {:vsn :new-vsn
                                                   :native-prefix :new-native-prefix
                                                   :file :new-file}))
        join (merge-with merge renamed-old-deps renamed-new-raw-deps)
        new-native-path? (and old-native-path
                              (not= (io/file old-native-path) (io/file relative-native-path)))
        maybe-stale (volatile! false)]
    ;; Why all the warnings? Well, we cannot really delete a directory, as it
    ;; may be populated by things created by others, either in a prep-step
    ;; before us OR as manual work (adding some self-made native stuff into
    ;; said). :native-path does not have to be in :target, which makes this
    ;; stuff kind of hard to avoid. However, it is likely that this path is in
    ;; the :target-path, which makes life easier for us. TODO: Fix this up for
    ;; Lein 3.0 by enforcing native to be inside :target-path and stating that
    ;; it should only be used by native deps.
    (when new-native-path?
      (warn-once "Warning: You changed :native-path to" (pr-str relative-native-path)
                 ", but old native data is still available at" (pr-str old-native-path))
      (vreset! maybe-stale true)
      (doseq [[_ {:keys [native-prefix file]}] new-raw-deps]
        (extract-native-dep! native-path file native-prefix)))
    (let [newly-extracted-deps
          (->>
           (for [[dep {:keys [old-vsn old-native-prefix old-native?
                              new-vsn new-native-prefix new-file]}] join]
             (cond (and (= old-vsn new-vsn) ;; no change, stuff already in directory
                        (= old-native-prefix new-native-prefix))
                   [dep {:vsn old-vsn
                         :native-prefix old-native-prefix
                         :native? old-native?}]

                   (nil? old-vsn) ;; no old version, attempt to extract
                   (let [native? (extract-native-dep! native-path new-file new-native-prefix)]
                     [dep {:vsn new-vsn
                           :native-prefix new-native-prefix
                           :native? native?}])

                   (nil? new-vsn) ;; dependency was removed
                   (when (and (not new-native-path?) old-native?)
                     (vreset! maybe-stale true)
                     (warn-once "Warning:" dep old-vsn "will still have its native content in :native-path"))

                   ;; prefix changed (possibly version as well)
                   (not= old-native-prefix new-native-prefix)
                   (let [native? (extract-native-dep! native-path new-file new-native-prefix)]
                     (when (and (not new-native-path?) old-native?)
                       (vreset! maybe-stale true)
                       (warn-once "Warning:" dep "had its native prefix changed, but content"
                                  "from"  (pr-str old-native-prefix) "is still in :native-path"))
                     [dep {:vsn new-vsn
                           :native-prefix new-native-prefix
                           :native? native?}])

                   ;; version changed (all options are now exhausted)
                   (not= old-vsn new-vsn)
                   (let [native? (extract-native-dep! native-path new-file new-native-prefix)]
                     (when (and (not new-native-path?) old-native?)
                       (vreset! maybe-stale true)
                       (warn-once "Warning: Native dependencies from the old version of"
                                  dep (str "(" old-vsn ") is still in :native-path")))
                     [dep {:vsn new-vsn
                           :native-prefix new-native-prefix
                           :native? native?}])))
           (filter identity)
           (into {}))]
      (when @maybe-stale
        (warn-once "  Consider doing `lein clean` to remove potentially stale native files"))
      {:native-path relative-native-path
       :dependencies newly-extracted-deps})))

(defn- read-string-or-error
  "Like read-string, but will return ::error if the reader threw an error."
  [s]
  (try
    (read-string s)
    (catch Exception e
       ::error)))

(defn outdated-swap!
  "Performs f if cmp-val is not equal to the old compare value. f is
  then called with (f outdated-val args...). If no previous cached
  result is found, then outdated-val is set to nil.

  The comparison value and cached value is stored
  in :target-path/stale/`identifier`. Make sure your identifier is
  unique, e.g. by providing your namespace and function name. The
  values will be read through read-string and printed with pr-str.

  outdated-swap! will not run outside of projects."
  [project identifier cmp-val f & args]
  (when (and (:root project) (:target-path project))
    (let [file (io/file (:target-path project) "stale" identifier)
          file-content (if (.exists file)
                         (read-string-or-error (slurp file)))
          [old-cmp-val outdated-val] (if (not= ::error file-content)
                                       file-content)]
      (when (= ::error file-content)
        (warn-once "Could not read the old stale value for" identifier ", rerunning stale task"))
      (when (or (= ::error file-content)
                (not= old-cmp-val cmp-val))
        (utils/mkdirs (.getParentFile file))
        (let [result (apply f outdated-val args)]
          (spit file (pr-str [cmp-val result]))
          result)))))

(defn ^:deprecated when-stale
  "DEPRECATED: Use outdated-swap! instead.

  Call f with args when keys in project.clj have changed since the last
  run. Stores value of project keys in stale directory inside :target-path.
  Because multiple callers may check the same keys, you must also provide a
  token to keep your stale value separate. Returns true if the code was executed
  and nil otherwise."
  [token keys project f & args]
  (warn-once "leiningen.core.classpath/when-stale is deprecated, use outdated-swap! instead.")
  (let [file (io/file (:target-path project) "stale"
                      (str (name token) "." (str/join "+" (map name keys))))
        current-value (pr-str (map (juxt identity project) keys))
        old-value (and (.exists file) (slurp file))]
    (when (and (:name project) (:target-path project)
               (not= current-value old-value))
      (apply f args)
      (utils/mkdirs (.getParentFile file))
      (spit file (doall current-value))
      true)))

;; The new version of aether (lein 2.8.0+) is more strict about authentication
;; settings; it will ignore :passphrase if :private-key-file is not set.
;; s3-wagon-private uses :passphrase, so if you've got a private s3 repo, we'll
;; set :private-key-file to an empty string here so that aether will allow the
;; wagon to see it instead of silently discarding it.
(defn- hack-private-key [repo]
  (if-not (re-find #"^s3p" (:url repo ""))
    repo
    (update repo :private-key-file #(or % ""))))

(defn add-repo-auth
  "Repository credentials (a map containing some of
  #{:username :password :passphrase :private-key-file}) are discovered
  from:

  1. Looking up the repository URL in the ~/.lein/credentials.clj.gpg map
  2. Scanning that map for regular expression keys that match the
     repository URL.

  So, a credentials map that contains an entry:

    {#\"http://maven.company.com/.*\" {:username \"abc\" :password \"xyz\"}}

  would be applied to all repositories with URLs matching the regex key
  that didn't have an explicit entry."
  [[id repo]]
  [id (-> repo user/profile-auth user/resolve-credentials hack-private-key)])

(defn get-non-proxy-hosts []
  (let [system-no-proxy (System/getenv "no_proxy")
        lein-no-proxy (System/getenv "http_no_proxy")]
    (if (and (empty? lein-no-proxy) (not-empty system-no-proxy))
      (->> (str/split system-no-proxy #",")
           (map #(str "*" %))
           (str/join "|"))
      (System/getenv "http_no_proxy"))))

(defn get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
   (let [proxy (System/getenv key)]
     (when-not (str/blank? proxy)
       (let [url (utils/build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host (.getHost url)
          :port (.getPort url)
          :username username
          :password password
          :non-proxy-hosts (get-non-proxy-hosts)})))))

(defn- update-policies [update checksum [repo-name opts]]
  (let [project-policies (cond-> {}
                           update (assoc :update update)
                           checksum (assoc :checksum checksum))]
    [repo-name (merge project-policies opts)]))

(defn ^:internal default-aether-args
  "Returns a map of keyword arguments to be used with Pomegranate Aether
  dependency resolution for the given project."
  [{:keys [repositories local-repo offline? update checksum mirrors] :as project}]
  {:local-repo local-repo
   :offline? offline?
   :repositories (->> repositories
                      (map add-repo-auth)
                      (map (partial update-policies update checksum)))
   :mirrors (->> mirrors
                 (map add-repo-auth)
                 (map (partial update-policies update checksum)))
   :proxy (get-proxy-settings)})

(defn- print-failures [e]
  (doseq [result (.getArtifactResults (.getResult e))
          :when (not (.isResolved result))
          exception (.getExceptions result)]
    (warn (.getMessage exception)))
  (doseq [ex (.getCollectExceptions (.getResult e))]
    (warn (.getMessage ex))))

(defn- root-cause [e]
  (last (take-while identity (iterate (memfn getCause) e))))

(def ^:private ^:dynamic *dependencies-session*
  "This is dynamic in order to avoid memoization issues.")

(defn- get-dependencies*
  [dependencies-key managed-dependencies-key
   {:keys [offline?] :as project}
   {:keys [add-classpath?] :as args}]
  {:pre [(every? vector? (get project dependencies-key))
         (every? vector? (get project managed-dependencies-key))]}
  (try
    (apply
      (if add-classpath?
        pomegranate/add-dependencies
        aether/resolve-dependencies)
      (apply concat
        (merge
          (default-aether-args project)
          {:managed-coordinates (get project managed-dependencies-key)
           :coordinates (get project dependencies-key)
           :repository-session-fn *dependencies-session*
           :transfer-listener
           (bound-fn [e]
             (let [{:keys [type resource error]} e
                   {:keys [repository name size trace]} resource
                   aether-repos (if trace (.getRepositories (.getData trace)))
                   find-repo #(or (= (.getUrl %) repository)
                                  ;; sometimes the "base" url
                                  ;; doesn't have a slash on it
                                  (= (str (.getUrl %) "/") repository))]
               (when-let [repo (and (= type :started)
                                    (first (filter find-repo aether-repos)))]
                 (locking *err*
                   (warn "Retrieving" name "from" (.getId repo))))))})))
    (catch DependencyResolutionException e
      ;; Cannot recur from catch/finally so have to put this in its own defn
      (print-failures e)
      (warn "This could be due to a typo in :dependencies, file system permissions, or network issues.")
      (warn "If you are behind a proxy, try setting the 'http_proxy' environment variable.")
      (throw (ex-info "Could not resolve dependencies" {:suppress-msg true
                                                        :exit-code 1} e)))
    (catch Exception e
      (let [exception-cause (root-cause e)]
        (if (and (or (instance? java.net.UnknownHostException exception-cause)
                     (instance? java.net.NoRouteToHostException exception-cause))
                 (not offline?))
          (get-dependencies* dependencies-key managed-dependencies-key
                             (assoc project :offline? true) args)
          (throw e))))))

(def ^:private get-dependencies-memoized (memoize get-dependencies*))

(defn ^:internal get-dependencies [dependencies-key managed-dependencies-key
                                   project & args]
  (let [ranges (atom []), overrides (atom [])
        trimmed (select-keys project [dependencies-key managed-dependencies-key
                                      :repositories :checksum :local-repo
                                      :offline? :update :mirrors :memoize-buster])
        deps-result (binding [*dependencies-session* (pedantic/session
                                                      project ranges overrides)]
                      (get-dependencies-memoized dependencies-key
                                                 managed-dependencies-key
                                                 trimmed (apply hash-map args)))]
    (pedantic/do (:pedantic? project) @ranges @overrides)
    deps-result))

(defn- get-original-dependency
  "Return a match to dep (a single dependency vector) in
  dependencies (a dependencies vector, such as :dependencies in
  project.clj). Matching is done on the basis of the group/artifact id
  and version."
  [dep dependencies]
  (some (fn [v] ; not certain if this is the best matching fn
          (when (= (subvec dep 0 2) (subvec v 0 2 )) v))
        dependencies))

(defn get-native-prefix
  "Return the :native-prefix of a dependency vector, or nil."
  [[id version & {:as opts}]]
  (get opts :native-prefix))

(defn native-dependency-info
  "Returns the dependency information about a dependency on the form
  [id version native-prefix] if the dependency is not nil."
  [dependency]
  (if dependency
    (let [[id version & {:as opts}] dependency]
      [id version (get opts :native-prefix)])))

(defn- native-dependency-map
  "Given a dependencies vector (such as :dependencies in project.clj) and a
  dependencies tree, as returned by get-dependencies, return a map from
  dependency identifier to :vsn, :file and :native-prefix (may be nil) for ALL
  dependencies this project depends on -- including transitive ones."
  [dependencies dependencies-tree]
  (let [native-dep-info (->> (map #(or (get-original-dependency % dependencies) %)
                                  (keys dependencies-tree))
                             (map native-dependency-info))]
    (->> (aether/dependency-files dependencies-tree)
         (#(map vector % native-dep-info))
         (filter #(re-find #"\.(jar|zip)$" (.getName (first %))))
         (map (fn [[file [id version native-prefix]]]
                [id {:file file :vsn version :native-prefix native-prefix}]))
         (into {}))))

(defn- extract-native-dependencies
  "extract-native-dependencies calculates the native dependency map for all
  dependencies, including transitive ones. It then extracts new native content
  from native dependencies,"
  [{:keys [native-path dependencies] :as project} jars dependencies-tree]
  ;; FIXME: This is a hack for a bug I noticed (issue #2077): Sometimes the
  ;; project comes through without having an init-profiles call ran on it. This
  ;; means that native-path is on an uninitialised form. The sane way to fix
  ;; this up is to check if this is the case, and if so, just ignore it. (Don't
  ;; worry, this call is done a ton of times)
  ;; To check this, just check if the path is absolute:
  (when (and native-path (.isAbsolute (io/file native-path)))
    (let [relative-native-path (utils/relativize (:root project) native-path)
          native-dep-map (native-dependency-map dependencies dependencies-tree)
          snap-deps (utils/filter-vals native-dep-map
                                       #(.endsWith (:vsn %) "SNAPSHOT"))
          stale-check {:dependencies (utils/map-vals native-dep-map
                                                     #(select-keys % [:vsn :native-prefix]))
                       :native-path relative-native-path}]
      (or (outdated-swap!
           project "leiningen.core.classpath.extract-native-dependencies"
           stale-check
           stale-extract-native-deps
           native-dep-map
           relative-native-path
           native-path)
          ;; Always extract native deps from SNAPSHOT deps.
          (doseq [[_ {:keys [native-prefix file]}] snap-deps]
            (extract-native-dep! native-path file native-prefix))))))

(def ^:private bootclasspath-deps
  (if-let [deps-file (io/resource "leiningen/bootclasspath-deps.clj")]
    (read-string (slurp deps-file))
    {}))

(defn- warn-conflicts
  "When using the bootclasspath (for boot speed), resources already on the
  bootclasspath cannot be overridden by plugins, so notify the user about it."
  [project dependencies]
  (when (#{:warn :abort} (:pedantic? project))
    (let [warned (atom false)]
      (doseq [[artifact version] dependencies
              :when (and (bootclasspath-deps artifact)
                         (not= (bootclasspath-deps artifact) version))]
        (reset! warned true)
        (warn-once "Tried to load" artifact "version" version "but"
                   (bootclasspath-deps artifact) "was already loaded."))
      (when (and @warned
                 (not (:root project))
                 (not (:suppress-conflict-warnings project)))
        (warn-once "You can set :eval-in :subprocess in your :user profile;"
                   "however this will increase repl load time.")))))

(defn resolve-managed-dependencies
  "Delegate dependencies to pomegranate. This will ensure they are
  downloaded into ~/.m2/repository and that native components of
  dependencies have been extracted to :native-path. If :add-classpath?
  is logically true, will add the resolved dependencies to Leiningen's
  classpath.

  Supports inheriting 'managed' dependencies, e.g. to allow common dependency
  versions to be specified from an alternate location in the project file, or
  from a parent project file.

  Returns a seq of the dependencies' files."
  [dependencies-key managed-dependencies-key project & rest]
  (let [dependencies-tree (apply get-dependencies dependencies-key
                                 managed-dependencies-key project rest)
        jars (->> dependencies-tree
                  (aether/dependency-files)
                  (filter #(re-find #"\.(jar|zip)$" (.getName %))))]
    (when (some #{:add-classpath?} rest)
      (warn-conflicts project (concat (keys dependencies-tree)
                                      (reduce into (vals dependencies-tree)))))
    (when (and (= :dependencies dependencies-key)
               (:root project))
      (extract-native-dependencies project jars dependencies-tree))
    jars))

(defn ^:deprecated resolve-dependencies
  "Delegate dependencies to pomegranate. This will ensure they are
  downloaded into ~/.m2/repository and that native components of
  dependencies have been extracted to :native-path. If :add-classpath?
  is logically true, will add the resolved dependencies to Leiningen's
  classpath.

  Returns a seq of the dependencies' files.

  NOTE: deprecated in favor of `resolve-managed-dependencies`."
  [dependencies-key project & rest]
  (let [managed-dependencies-key (if (= dependencies-key :dependencies)
                                   :managed-dependencies)]
    (apply resolve-managed-dependencies dependencies-key managed-dependencies-key project rest)))

(defn normalize-dep-vector
  "Normalize the vector for a single dependency, to ensure it is compatible with
  the format expected by pomegranate.  The main purpose of this function is to
  to detect the case where the version string for a dependency has been omitted,
  due to the use of `:managed-dependencies`, and to inject a `nil` into the
  vector in the place where the version string should be."
  [dep]
  ;; Some plugins may replace a keyword with a version string later on, so
  ;; assume that even length vectors are alright. If not, then they will blow up
  ;; at a later stage.
  (if (even? (count dep))
    dep
    (let [id (first dep)
          opts (rest dep)]
      ;; it's important to preserve the metadata, because it is used for
      ;; profile merging, etc.
      (with-meta
       (into [id nil] opts)
       (meta dep)))))

(defn normalize-dep-vectors
  "Normalize the vectors for the `:dependencies` section of the project.  This
  ensures that they are compatible with the format expected by pomegranate.
  The main purpose of this function is to to detect the case where the version
  string for a dependency has been omitted, due to the use of `:managed-dependencies`,
  and to inject a `nil` into the vector in the place where the version string
  should be."
  [deps]
  (map normalize-dep-vector deps))

(defn merge-versions-from-managed-coords
  [deps managed-deps]
  (aether/merge-versions-from-managed-coords
   (normalize-dep-vectors deps)
   managed-deps))

(defn managed-dependency-hierarchy
  "Returns a graph of the project's dependencies.

  Supports inheriting 'managed' dependencies, e.g. to allow common dependency
  versions to be specified from an alternate location in the project file, or
  from a parent project file."
  [dependencies-key managed-dependencies-key project & options]
  (if-let [deps-list (merge-versions-from-managed-coords
                      (get project dependencies-key)
                      (get project managed-dependencies-key))]
    (aether/dependency-hierarchy deps-list
                                 (apply get-dependencies dependencies-key
                                        managed-dependencies-key
                                        project options))))

(defn dependency-hierarchy
  "Returns a graph of the project's dependencies."
  [dependencies-key project & options]
  (apply managed-dependency-hierarchy dependencies-key nil project options))

(defn- normalize-path [root path]
  (let [f (io/file path) ; http://tinyurl.com/ab5vtqf
        abs (.getAbsolutePath (if (or (.isAbsolute f)
                                      (.startsWith (.getPath f) "\\"))
                                f (io/file root path)))
        sep (System/getProperty "path.separator")]
    (str/replace abs sep (str "\\" sep))))

(defn ext-dependency?
  "Should the given dependency be loaded in the extensions classloader?"
  [dep]
  (second
   (some #(if (= :ext (first %)) dep)
         (partition 2 dep))))

(defn ext-classpath
  "Classpath of the extensions dependencies in project as a list of strings."
  [project]
  (seq
   (->> (filter ext-dependency? (:dependencies project))
        (assoc project :dependencies)
        (resolve-managed-dependencies :dependencies :managed-dependencies)
        (map (memfn getAbsolutePath)))))

(defn- visit-project!
  "Records a visit to a project into the volatile `seen`, returning nil if the project has already been visited."
  [seen {:keys [root] :as project}]
  (when-not (@seen root)
    (vswap! seen conj root)
    project))

(defn- project-paths
  "Returns a function that applies each function in checkout-paths to a given project and returns a flattened list of
  classpath entries."
  [checkout-paths]
  (if (seq checkout-paths)
    (comp flatten (apply juxt checkout-paths))
    (constantly nil)))

(def ^:internal ^:dynamic *seen* nil)

(defn ^:internal checkout-deps-paths
  "Checkout dependencies are used to place source for a dependency
  project directly on the classpath rather than having to install the
  dependency and restart the dependent project."
  [{:keys [checkout-deps-shares root] :as project}]
  (require 'leiningen.core.project)
  (try
    ;; This function needs to be re-entrant as it is one of the default members of `:checkout-deps-shares`.
    ;; Use *seen* to communicate visit state between invocations.
    (binding [*seen* (or *seen* (volatile! #{root}))]
      ;; Visit each project and accumulate classpaths into a vector. This cannot be lazy as *seen* must be bound.
      (into []
            (comp (keep (partial visit-project! *seen*))
                  (mapcat (project-paths checkout-deps-shares)))
            ((resolve 'leiningen.core.project/read-checkouts) project)))
    (catch Exception e
      (throw (Exception. (format "Problem loading %s checkouts" project) e)))))

(defn get-classpath
  "Return the classpath for project as a list of strings."
  [project]
  (for [path (concat (:test-paths project)
                     (:source-paths project)
                     (:resource-paths project)
                     [(:compile-path project)]
                     (checkout-deps-paths project)
                     (for [dep (resolve-managed-dependencies
                                :dependencies :managed-dependencies project)]
                       (.getAbsolutePath dep)))
        :when path]
    (normalize-path (:root project) path)))
