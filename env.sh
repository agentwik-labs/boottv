# Source this: `source env.sh` — sets up the local JDK + Android SDK for BootTV builds.
export JAVA_HOME="$(ls -d "$HOME"/.jdks/jdk-17*/Contents/Home | head -1)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
