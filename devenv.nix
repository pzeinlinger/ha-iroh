{ pkgs, ... }:
{
  languages.rust = {
    enable = true;
    channel = "stable";
    targets = [ "aarch64-linux-android" "x86_64-linux-android" ];
  };
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  # uniffi-bindgen is not packaged in nixpkgs; added with the uniffi ticket.
  packages = [ pkgs.cargo-ndk ];

  # Android SDK/NDK come from the system Android Studio install, not Nix.
  # NDK_HOME = newest installed NDK (empty until installed via SDK Manager).
  enterShell = ''
    export ANDROID_HOME="''${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    export ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
  '';
}
