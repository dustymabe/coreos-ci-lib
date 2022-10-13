// Run kola tests on the latest build in the cosa dir
// Available parameters:
//    addExtTests:       []string -- list of test paths to run
//    allowUpgradeFail   boolean  -- warn instead of fail on upgrade failure
//    arch:              string   -- the target architecture
//    cosaDir:           string   -- cosa working directory
//    parallel:          integer  -- number of tests to run in parallel (default: # CPUs)
//    skipBasicScenarios boolean  -- skip basic qemu scenarios
//    skipUpgrade:       boolean  -- skip running `cosa kola --upgrades`
//    build:             string   -- cosa build ID to target
//    platformArgs:      string   -- platform-specific kola args (e.g. '-p aws --aws-ami ...`)
//    extraArgs:         string   -- additional kola args for `kola run` (e.g. `ext.*`)
//    disableRerun:      boolean  -- disable reruns of failed tests
//    marker:            string   -- some identifying text to add to uploaded artifact filenames
def call(params = [:]) {
    def cosaDir = utils.getCosaDir(params)

    // this is shared between `kola run` and `kola run-upgrade`
    def platformArgs = params.get('platformArgs', "");
    def buildID = params.get('build', "latest");
    def arch = params.get('arch', "x86_64");
    def marker = params.get('marker', "kola");
    def rerun = "--rerun"
    if (params.get('disableRerun', false)) {
        rerun = ""
    }
    def archArg = "--arch=${arch}"

    // Define a unique token to be added to the file name uploads
    // Prevents multiple runs overwriting same filename in archiveArtifacts
    def token = shwrapCapture("uuidgen | cut -f1 -d-")

    // Create a unique output directory for this run of kola
    def outputDir = shwrapCapture("cosa shell -- mktemp -d ${cosaDir}/tmp/kola-XXXXX")

    // list of identifiers for each run for log collection
    def Ids = []

    // This is a bit obscure; what we're doing here is building a map of "name"
    // to "closure" which `parallel` will run in parallel. That way, we can
    // conditionally only add the `run_upgrades` stage if not explicitly
    // skipped.
    def kolaRuns = [:]
    kolaRuns["${arch}:Kola"] = {
        def args = ""
        def id
        // Add the tests/kola directory, but only if it's not the same as the
        // src/config repo which is also automatically added.
        if (shwrapRc("""
            test -d ${env.WORKSPACE}/tests/kola
            configorigin=\$(cd ${cosaDir}/src/config && git config --get remote.origin.url)
            gitorigin=\$(cd ${env.WORKSPACE} && git config --get remote.origin.url)
            test "\$configorigin" != "\$gitorigin"
        """) == 0)
        {
            // The workspace name created by Jenkins is messy and dynamic, but
            // kola uses it to derive the test name. Let's fix it by using a
            // symlinked dir (x86_64) or copied dir (multi-arch).
            def name = shwrapCapture("basename \$(git config --get remote.origin.url) .git")
            if (arch == 'x86_64') {
                shwrap("mkdir -p /var/tmp/kola && ln -s ${env.WORKSPACE} /var/tmp/kola/${name}")
            } else {
                shwrap("""
                cosa shell -- mkdir -p /var/tmp/kola
                cosa remote-session sync ${env.WORKSPACE}/ :/var/tmp/kola/${name}/
                """)
            }
            args += "--exttest /var/tmp/kola/${name}"
        }
        def parallel = params.get('parallel', "auto");
        def extraArgs = params.get('extraArgs', "");
        def addExtTests = params.get('addExtTests', [])

        for (path in addExtTests) {
            args += " --exttest ${env.WORKSPACE}/${path}"
        }

        def isQEMU = platformArgs == "" ? true : false

        if (!isQEMU) {
            // For cloud (non qemu) tests we don't need to worry about
            // basic-qemu-scenarios and we don't need to worry about
            // resources usage so we don't need to run reprovision
            // tests separately. Run them all up front.
            id = marker == "" ? "kola" : "kola-${marker}"
            Ids += id
            shwrap("cosa kola run ${rerun} --output-dir=${outputDir}/${id} --build=${buildID} ${archArg} ${platformArgs} --parallel ${parallel} ${args} ${extraArgs}")
        } else {
            // basic run
            if (!params['skipBasicScenarios']) {
                id = marker == "" ? "kola-basic" : "kola-basic-${marker}"
                Ids += id
                shwrap("cosa kola run ${rerun} --output-dir=${outputDir}/${id} --basic-qemu-scenarios")
            }
            // normal run (without reprovision tests because those require a lot of memory)
            id = marker == "" ? "kola" : "kola-${marker}"
            Ids += id
            shwrap("cosa kola run ${rerun} --output-dir=${outputDir}/${id} --denylist-test basic --build=${buildID} ${archArg} ${platformArgs} --tag '!reprovision' --parallel ${parallel} ${args} ${extraArgs}")

            // re-provision tests (not run with --parallel argument to kola)
            id = marker == "" ? "kola-reprovision" : "kola-reprovision-${marker}"
            Ids += id
            shwrap("cosa kola run ${rerun} --output-dir=${outputDir}/${id} --build=${buildID} ${archArg} ${platformArgs} --tag reprovision ${args} ${extraArgs}")
        }
    }

    if (!params["skipUpgrade"]) {
        kolaRuns["${arch}:Kola:upgrade"] = {
            // If upgrades are broken `cosa kola --upgrades` might
            // fail to even find the previous image so we wrap this
            // in a try/catch so ALLOW_KOLA_UPGRADE_FAILURE can work.
            try {
                def id = marker == "" ? "kola-upgrade" : "kola-upgrade-${marker}"
                Ids += id
                shwrap("cosa kola ${rerun} --output-dir=${outputDir}/${id} --upgrades --build=${buildID} ${archArg} ${platformArgs}")
            } catch(e) {
                if (params["allowUpgradeFail"]) {
                    warnError(message: 'Upgrade Failed') {
                        error(e.getMessage())
                    }
                } else {
                    throw e
                }
            }
        }
    }

    stage('Kola') {
        dir(cosaDir) {
            try {
                if (kolaRuns.size() == 1) {
                    kolaRuns.each { k, v -> v() }
                } else {
                    parallel(kolaRuns)
                }
            } finally {
                for (id in Ids) {
                    // sanity check kola actually ran and dumped its output
                    shwrap("cosa shell -- test -d ${outputDir}/${id}")
                    // collect the output
                    shwrap("cosa shell -- tar -c --xz ${outputDir}/${id} > ${env.WORKSPACE}/${id}-${token}.tar.xz")
                    archiveArtifacts allowEmptyArchive: true, artifacts: "${id}-${token}.tar.xz"
                }
            }
        }
    }
}
