{
  description = "Development shell for boomslang";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    {
      nixpkgs,
      ...
    }:
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

          python = pkgs.python3.withPackages (
            ps: with ps; [
              pip
              setuptools
              wheel
            ]
          );

          wasiSdkRelease = {
            aarch64-darwin = {
              platform = "arm64-macos";
              hash = "sha256-Hbpw5ai4R5n3o6qtklS45QsFbe3p7gtUmEmR+94mHeQ=";
            };
            x86_64-darwin = {
              platform = "x86_64-macos";
              hash = "sha256-o1KhK8eP0aZR/+pzU7s0JZJ3gmz/aDBs9aVikBBnFCg=";
            };
            aarch64-linux = {
              platform = "arm64-linux";
              hash = "sha256-3ulmXsL1S3UGJ6APH3B1qsyzPZhGF2aRnF2cIrZJ+sg=";
            };
            x86_64-linux = {
              platform = "x86_64-linux";
              hash = "sha256-/cyLxhFsfBBQxn4NrhLdbgHjU3YUjYhPnvquWJodcO8=";
            };
          }.${system};

          wasiSdk = pkgs.fetchzip {
            url = "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-24/wasi-sdk-24.0-${wasiSdkRelease.platform}.tar.gz";
            hash = wasiSdkRelease.hash;
            stripRoot = true;
          };
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.binaryen
              pkgs.cmake
              pkgs.coreutils
              pkgs.curl
              pkgs.findutils
              pkgs.gh
              pkgs.git
              pkgs.gnumake
              pkgs.gnutar
              pkgs.gzip
              pkgs.jdk21
              pkgs.just
              pkgs.maven
              pkgs.pkg-config
              pkgs.rsync
              pkgs.rust-analyzer
              pkgs.rustup
              pkgs.wabt
              pkgs.wizer
              pkgs.xz
              python
              wasiSdk
            ];

            JAVA_HOME = pkgs.jdk21.home;
            WASI_SDK_PATH = wasiSdk;
            CARGO_TARGET_WASM32_WASIP1_LINKER = "${wasiSdk}/bin/clang";
            CC_wasm32_wasip1 = "${wasiSdk}/bin/clang";
            CXX_wasm32_wasip1 = "${wasiSdk}/bin/clang++";
            AR_wasm32_wasip1 = "${wasiSdk}/bin/llvm-ar";
            CFLAGS_wasm32_wasip1 = "--sysroot=${wasiSdk}/share/wasi-sysroot";

            shellHook = ''
              export PATH="${wasiSdk}/bin:$PATH"
              export BOOMSLANG_CONTAINER_CLI="''${BOOMSLANG_CONTAINER_CLI:-docker}"

              echo "boomslang devshell"
              echo "  Java: $JAVA_HOME"
              echo "  Rust: rustup toolchain from rust-toolchain.toml"
              echo "  WASI SDK: $WASI_SDK_PATH"
              echo "  Container CLI: $BOOMSLANG_CONTAINER_CLI"
            '';
          };
        }
      );
    };
}
