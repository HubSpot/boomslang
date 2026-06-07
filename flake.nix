{
  description = "boomslang development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          jdk = pkgs.jdk21;
          jdkHome = if jdk ? home then jdk.home else jdk;
          mavenToolchains = pkgs.writeText "maven-toolchains.xml" ''
            <toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 https://maven.apache.org/xsd/toolchains-1.1.0.xsd">
              <toolchain>
                <type>jdk</type>
                <provides>
                  <version>21</version>
                </provides>
                <configuration>
                  <jdkHome>${jdkHome}</jdkHome>
                </configuration>
              </toolchain>
            </toolchains>
          '';
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk
              maven
              just
              python3
              git-lfs
              curl
              rsync
              gnutar
              gzip
            ];

            JAVA_HOME = jdkHome;

            shellHook = ''
              export PATH="$JAVA_HOME/bin:$PATH"
              if [ -n "''${MAVEN_ARGS:-}" ]; then
                export MAVEN_ARGS="--toolchains ${mavenToolchains} $MAVEN_ARGS"
              else
                export MAVEN_ARGS="--toolchains ${mavenToolchains}"
              fi
              echo "boomslang dev shell: Java $(${jdk}/bin/java -version 2>&1 | head -n 1), Maven $(mvn --version | head -n 1)"
            '';
          };
        }
      );
    };
}
