"""Build the Kidscreen APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.

Paths can be overridden with environment variables:
    ANDROID_DEV          Android SDK-ish root      (default C:\\Android\\dev)
    ANDROID_BUILD_TOOLS  build-tools directory     (default <DEV>\\android-14)
    ANDROID_JAR          android.jar               (default <DEV>\\android-13\\android.jar)
    JAVA_BIN             JDK bin directory         (default <DEV>\\jdk-17...\\bin)
"""
import os
import shutil
import subprocess
import sys
import zipfile

DEV = os.environ.get("ANDROID_DEV", r"C:\Android\dev")
BT = os.environ.get("ANDROID_BUILD_TOOLS", os.path.join(DEV, "android-14"))
ANDROID_JAR = os.environ.get("ANDROID_JAR", os.path.join(DEV, "android-13", "android.jar"))
JDK = os.environ.get("JAVA_BIN", os.path.join(DEV, "jdk-17.0.21.1+2", "bin"))
SRC = os.path.dirname(os.path.abspath(__file__))

ENV = dict(os.environ)
ENV["PATH"] = JDK + os.pathsep + ENV.get("PATH", "")


def run(desc, args, cwd=None):
    print("--- %s ---" % desc)
    r = subprocess.run(args, cwd=cwd, env=ENV, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    if out.strip():
        print(out.strip()[:3000])
    if r.returncode != 0:
        print("FAILED rc=%d" % r.returncode)
        sys.exit(1)
    return out


def main():
    for d in ("classes", "dexout", "gen"):
        p = os.path.join(SRC, d)
        if os.path.isdir(p):
            shutil.rmtree(p)
    for f in ("base.apk", "aligned.apk", "app.apk", "compiled.zip", "app.apk.idsig"):
        p = os.path.join(SRC, f)
        if os.path.exists(p):
            os.remove(p)

    # 1. 编译资源（无障碍服务的配置 XML + 字符串）
    run("aapt2 compile", [
        os.path.join(BT, "aapt2.exe"), "compile",
        "--dir", "res", "-o", "compiled.zip",
    ], cwd=SRC)

    # 2. 链接 -> base.apk
    run("aapt2 link", [
        os.path.join(BT, "aapt2.exe"), "link",
        "-o", "base.apk",
        "-I", ANDROID_JAR,
        "--manifest", "AndroidManifest.xml",
        "--min-sdk-version", "28",
        "--target-sdk-version", "33",
        "compiled.zip",
    ], cwd=SRC)

    # 3. javac
    java_files = []
    for root, _, files in os.walk(os.path.join(SRC, "src")):
        for fn in files:
            if fn.endswith(".java"):
                java_files.append(os.path.join(root, fn))
    run("javac", [
        os.path.join(JDK, "javac.exe"),
        "-source", "8", "-target", "8",
        "-encoding", "UTF-8",
        "-classpath", ANDROID_JAR,
        "-d", "classes",
    ] + java_files, cwd=SRC)

    # 4. d8 -> classes.dex
    class_files = []
    for root, _, files in os.walk(os.path.join(SRC, "classes")):
        for fn in files:
            if fn.endswith(".class"):
                class_files.append(os.path.join(root, fn))
    os.makedirs(os.path.join(SRC, "dexout"), exist_ok=True)
    run("d8", ["cmd", "/c", os.path.join(BT, "d8.bat"),
               "--lib", ANDROID_JAR, "--min-api", "28",
               "--output", "dexout"] + class_files, cwd=SRC)

    # 5. 把 dex 塞进 apk
    dex = os.path.join(SRC, "dexout", "classes.dex")
    if not os.path.exists(dex):
        print("classes.dex missing")
        sys.exit(1)
    apk = os.path.join(SRC, "base.apk")
    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
    print("--- added classes.dex (%d bytes) ---" % os.path.getsize(dex))

    # 6. zipalign
    run("zipalign", [
        os.path.join(BT, "zipalign.exe"), "-f", "-p", "4",
        "base.apk", "aligned.apk",
    ], cwd=SRC)

    # 7. 签名
    ks = os.path.join(SRC, "debug.keystore")
    if not os.path.exists(ks):
        run("keytool", [
            os.path.join(JDK, "keytool.exe"), "-genkeypair",
            "-keystore", ks, "-alias", "androiddebugkey",
            "-storepass", "android", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US",
        ], cwd=SRC)

    run("apksigner", [
        "cmd", "/c", os.path.join(BT, "apksigner.bat"), "sign",
        "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--out", "app.apk", "aligned.apk",
    ], cwd=SRC)

    final = os.path.join(SRC, "app.apk")
    print("\nBUILD OK: %s (%d bytes)" % (final, os.path.getsize(final)))
    run("verify", ["cmd", "/c", os.path.join(BT, "apksigner.bat"),
                   "verify", "--print-certs", "app.apk"], cwd=SRC)


if __name__ == "__main__":
    main()
