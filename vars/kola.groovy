// Run kola tests on the latest build in the cosa dir
// Available parameters:
//    addExtTests:        []string -- list of test paths to run
//    allowUpgradeFail    boolean  -- warn instead of fail on upgrade failure
//    disableRerunSuccess boolean  -- disable rerun success (the default)
//    rerunSuccessArgs    string   -- override rerun success args (default: tags=needs-internet)
//    arch:               string   -- the target architecture
//    cosaDir:            string   -- cosa working directory
//    parallel:           integer  -- number of tests to run in parallel (default: # CPUs)
//    skipBasicScenarios  boolean  -- skip basic qemu scenarios
//    skipSecureBoot      boolean  -- skip secureboot tests
//    skipUpgrade:        boolean  -- skip running `cosa kola --upgrades`
//    build:              string   -- cosa build ID to target
//    platformArgs:       string   -- platform-specific kola args (e.g. '-p aws --aws-ami ...`)
//    extraArgs:          string   -- additional kola args for `kola run` (e.g. `ext.*`)
//    disableRerun:       boolean  -- disable reruns of failed tests
//    marker:             string   -- some identifying text to add to uploaded artifact filenames
def call(params = [:]) {
    def cosaDir = utils.getCosaDir(params)

    // this is shared between `kola run` and `kola run-upgrade`
    def platformArgs = params.get('platformArgs', "");
    def buildID = params.get('build', "latest");
    def arch = params.get('arch', "x86_64");
    def marker = params.get('marker', "");
    def rerun = ""
    if (!params.get('disableRerun', false)) {
        rerun = "--rerun"
        if (!params.get('disableRerunSuccess', false)) {
            // by default we'll allow rerun success for tests that need internet, but this can be overridden
            // by passing in a value to allowRerunSuccessArgs
            rerun += " --allow-rerun-success="
            rerun += params.get('rerunSuccessArgs', "tags=needs-internet")
        }
    }
    def archArg = "--arch=${arch}"

    // Define a unique token to be added to the file name uploads
    // Prevents multiple runs overwriting same filename in archiveArtifacts
    def token = shwrapCapture("uuidgen | cut -f1 -d-")

    // Create a unique output directory for this run of kola
    def outputDir = shwrapCapture("cd ${cosaDir} && cosa shell -- mktemp -d ${cosaDir}/tmp/kola-XXXXX")

    // list of identifiers for each run for log collection
    def ids = []

    // If given a marker then add it to the parallel run stage titles
    def titleMarker = marker == "" ? "" : "${marker}:"

    // A closure to help run kola. Common arguments and Error/Warning
    // handling are consolidated in this function as a convenience.
    def runKola = { id, action, args ->
        def rc = shwrapRc("""
            cd ${cosaDir}
            cosa kola ${action} ${rerun} --build=${buildID} --output-dir=${outputDir}/${id} \
                --on-warn-failure-exit-77 ${archArg} ${platformArgs} ${args}
        """)
        if (rc == 77) {
            warn("A warn:true test failed")
        } else if (rc != 0) {
            error("Script returned exit code ${rc}")
        }
    }

    // This is a bit obscure; what we're doing here is building a map of "name"
    // to "closure" which `parallel` will run in parallel. That way, we can
    // conditionally only add the `run_upgrades` stage if not explicitly
    // skipped.
    def kolaRuns = [:]
    if (!params["skipUpgrade"]) {
        kolaRuns["${titleMarker}kola:upgrade"] = {
            // If upgrades are broken `cosa kola --upgrades` might
            // fail to even find the previous image so we wrap this
            // in a try/catch so allowUpgradeFail can work.
            def id = marker == "" ? "kola-upgrade" : "kola-upgrade-${marker}"
            ids += id
            try {
                runKola(id, 'run-upgrade',  "--upgrades")
            } catch(e) {
                // If we didn't even get logs then let's remove them from the list
                if (shwrapRc("cd ${cosaDir} && cosa shell -- test -d ${outputDir}/${id}") != 0) {
                    ids.remove(id)
                }
                if (params["allowUpgradeFail"]) {
                    warn(e.getMessage())
                } else {
                    throw e
                }
            }
        }
    }

    try {
        parallel(kolaRuns)
    } finally {
        for (id in ids) {
            // sanity check kola actually ran and dumped its output
            shwrap("cd ${cosaDir} && cosa shell -- test -d ${outputDir}/${id}")
            // collect the output
            shwrap("cd ${cosaDir} && cosa shell -- tar -C ${outputDir} -c --xz ${id} > ${env.WORKSPACE}/${id}-${token}.tar.xz || :")
            archiveArtifacts allowEmptyArchive: true, artifacts: "${id}-${token}.tar.xz"
        }
    }
}
