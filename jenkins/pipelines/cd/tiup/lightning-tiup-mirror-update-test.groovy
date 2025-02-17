def tiup_desc = ""
def desc = "TiDB Lightning is a tool used for fast full import of large amounts of data into a TiDB cluster"

def tiflash_sha1, tarball_name, dir_name

def download = { name, version, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    }  else {
        sh """
        exit 1
        """
    }

    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${lightning_sha1}/${platform}/${tarball_name}
    """

}

def unpack = { name, version, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->

    sh """
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package
    """

    sh """
    tar -C bin/ -czvf package/tidb-lightning-${version}-${os}-${arch}.tar.gz tidb-lightning
    rm -rf bin
    """

    sh """
    tiup mirror publish tidb-lightning ${TIDB_VERSION} package/tidb-lightning-${version}-${os}-${arch}.tar.gz tidb-lightning --standalone --arch ${arch} --os ${os} --desc="${desc}"
    """
}

def update = { name, version, os, arch ->
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
}

try {
    node("build_go1130") {
        container("golang") {
            stage("Prepare") {
                deleteDir()
            }
            retry(5) {
                sh """
                wget -qnc https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/cd/tiup/tiup_utils.groovy
                """
            }
            
            def util = load "tiup_utils.groovy"

            stage("Install tiup") {
                util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
            }

            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                tag = RELEASE_TAG
                if(ORIGIN_TAG != "") {
                    lightning_sha1 = ORIGIN_TAG
                } else if (RELEASE_TAG >= "v5.2.0") {
                    lightning_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                } else {
                    lightning_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }

                if (TIDB_VERSION == "") {
                    TIDB_VERSION = RELEASE_TAG
                }
                // After v4.0.11, we use br repo instead of br repo, and we should not maintain old version, if we indeed need, we can use the old version of this groovy file
            }

            if (params.ARCH_X86) {
                stage("tiup release tidb-lightning linux amd64") {
                    update "br", RELEASE_TAG, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("tiup release tidb-lightning linux arm64") {
                    update "br", RELEASE_TAG, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("tiup release tidb-lightning darwin amd64") {
                    update "br", RELEASE_TAG, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM && RELEASE_TAG >="v5.1.0") {
                stage("tiup release tidb-lightning darwin arm64") {
                    update "br", RELEASE_TAG, "darwin", "arm64"
                }
            }
        }
    }
} catch (Exception e) {
    echo "${e}"
    throw e
}