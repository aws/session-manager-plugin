COPY := cp -p
GO_BUILD := go build

# Default build configuration, can be overridden at build time.
GOARCH?=$(shell go env GOARCH)
GOOS?=$(shell go env GOOS)

GO_SPACE?=$(CURDIR)
GOTEMPPATH?=$(GO_SPACE)/build/private
GOTEMPCOPYPATH?=$(GOTEMPPATH)/src/github.com/aws/session-manager-plugin
GOPATH:=$(GOTEMPPATH):$(GO_SPACE)/vendor:$(GOPATH)

export GOPATH
export GO_SPACE
export GO111MODULE=auto

checkstyle::
#   Run checkstyle script
	$(GO_SPACE)/Tools/src/checkstyle.sh

build:: build-linux-amd64 build-linux-386 build-arm build-arm64 build-darwin-arm64 build-darwin-amd64 build-windows-amd64 build-windows-386

prepack:: prepack-linux-amd64 prepack-linux-386 prepack-linux-arm64 prepack-windows-386 prepack-windows-amd64

package:: create-package-folder package-rpm-amd64 package-rpm-386 package-rpm-arm64 package-deb-amd64 package-deb-386 package-deb-arm package-deb-arm64 package-darwin-arm64 package-darwin-amd64 package-win-386 package-win-amd64

release:: clean checkstyle release-test pre-release build prepack package copy-package-dependencies

clean:: remove-prepacked-folder
	rm -rf build/* bin/ .cover/
	find . -type f -name '*.log' -delete

.PHONY: release-test
release-test: pre-build copy-src pre-release quick-test

.PHONY: remove-prepacked-folder
remove-prepacked-folder:
	rm -rf $(GO_SPACE)/bin/prepacked

.PHONY: copy-src
copy-src:
	rm -rf $(GOTEMPCOPYPATH)
	mkdir -p $(GOTEMPCOPYPATH)
	@echo "copying files to $(GOTEMPCOPYPATH)"
	$(COPY) -r $(GO_SPACE)/src $(GOTEMPCOPYPATH)

.PHONY: pre-build
pre-build:
	for file in $(GO_SPACE)/Tools/src/*.sh; do chmod 755 $$file; done
	@echo "Build Session Manager Plugin "
	@echo "GOPATH=$(GOPATH)"
	rm -rf $(GO_SPACE)/build/bin/ $(GO_SPACE)/vendor/bin/
	mkdir -p $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/LICENSE $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/NOTICE $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/README.md $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/RELEASENOTES.md $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/THIRD-PARTY $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/seelog_unix.xml $(GO_SPACE)/bin/
	$(COPY) $(GO_SPACE)/seelog_windows.xml.template $(GO_SPACE)/bin/

	@echo "Regenerate version file during pre-release"
	go run $(GO_SPACE)/src/version/versiongenerator/version-gen.go
	$(COPY) $(GO_SPACE)/VERSION $(GO_SPACE)/bin/

.PHONY: pre-release
pre-release:
	@echo "session-manager-plugin release build"
	$(eval GO_BUILD := go build)
	rm -rf $(GO_SPACE)/vendor/pkg

.PHONY: quick-test
quick-test:
	# if you want to test a specific package, you can add the package name instead of the dots. Sample below
	# go test -gcflags "-N -l" github.com/aws/session-manager-plugin/src/datachannel
	go clean -testcache
	go test -cover -gcflags "-N -l" github.com/aws/session-manager-plugin/src/... -test.paniconexit0=false

.PHONY: create-package-folder
create-package-folder:
	mkdir -p $(GO_SPACE)/bin/updates/ssmcli/`cat $(GO_SPACE)/VERSION`/
	mkdir -p $(GO_SPACE)/bin/updates/sessionmanagerplugin/`cat $(GO_SPACE)/VERSION`/

.PHONY: build-linux-amd64
build-linux-amd64: checkstyle copy-src pre-build
	@echo "Build for linux platform"
	GOOS=linux GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/linux_amd64_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=linux GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/linux_amd64/ssmcli -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go

.PHONY: build-linux-386
build-linux-386: checkstyle copy-src pre-build
	@echo "Build for linux platform"
	GOOS=linux GOARCH=386 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/linux_386_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=linux GOARCH=386 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/linux_386/ssmcli -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go

.PHONY: build-arm
build-arm: checkstyle copy-src pre-build
	@echo "Build for ARM platform"
	GOOS=linux GOARCH=arm GOARM=6 $(GO_BUILD) -ldflags "-s -w -extldflags=-Wl,-z,now,-z,relro,-z,defs" -o $(GO_SPACE)/bin/linux_arm_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go

.PHONY: build-arm64
build-arm64: checkstyle copy-src pre-build
	@echo "Build for ARM64 platform"
	GOOS=linux GOARCH=arm64 $(GO_BUILD) -ldflags "-s -w -extldflags=-Wl,-z,now,-z,relro,-z,defs" -o $(GO_SPACE)/bin/linux_arm64_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go

.PHONY: build-darwin-arm64
build-darwin-arm64: checkstyle copy-src pre-build
	@echo "Build for darwin arm64 platform"
	GOOS=darwin GOARCH=arm64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/darwin_arm64_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=darwin GOARCH=arm64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/darwin_arm64/ssmcli -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go

.PHONY: build-darwin-amd64
build-darwin-amd64: checkstyle copy-src pre-build
	@echo "Build for darwin amd64 platform"
	GOOS=darwin GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/darwin_amd64_plugin/session-manager-plugin -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=darwin GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/darwin_amd64/ssmcli -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go

.PHONY: build-windows-amd64
build-windows-amd64: checkstyle copy-src pre-build
	@echo "Build for windows platform"
	GOOS=windows GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/windows_amd64_plugin/session-manager-plugin.exe -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=windows GOARCH=amd64 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/windows_amd64/ssmcli.exe -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go

.PHONY: build-windows-386
build-windows-386: checkstyle copy-src pre-build
	@echo "Build for windows platform"
	GOOS=windows GOARCH=386 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/windows_386_plugin/session-manager-plugin.exe -v \
		$(GO_SPACE)/src/sessionmanagerplugin-main/main.go
	GOOS=windows GOARCH=386 $(GO_BUILD) -ldflags "-s -w" -o $(GO_SPACE)/bin/windows_386/ssmcli.exe -v \
    		$(GO_SPACE)/src/ssmcli-main/main.go


.PHONY: prepack-linux-amd64
prepack-linux-amd64:
	mkdir -p $(GO_SPACE)/bin/prepacked/linux_amd64
	mkdir -p $(GO_SPACE)/bin/prepacked/linux_amd64_plugin
	$(COPY) $(GO_SPACE)/bin/linux_amd64_plugin/session-manager-plugin $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/session-manager-plugin
	$(COPY) $(GO_SPACE)/bin/seelog_unix.xml $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/seelog.xml
	$(COPY) $(GO_SPACE)/bin/LICENSE $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/LICENSE
	$(COPY) $(GO_SPACE)/bin/NOTICE $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/NOTICE
	$(COPY) $(GO_SPACE)/bin/README.md $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/README.md
	$(COPY) $(GO_SPACE)/bin/RELEASENOTES.md $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/RELEASENOTES.md
	$(COPY) $(GO_SPACE)/bin/THIRD-PARTY $(GO_SPACE)/bin/prepacked/linux_amd64_plugin/THIRD-PARTY
	$(COPY) $(GO_SPACE)/bin/linux_amd64/ssmcli $(GO_SPACE)/bin/prepacked/linux_amd64/ssmcli
	$(COPY) $(GO_SPACE)/bin/seelog_unix.xml $(GO_SPACE)/bin/prepacked/linux_amd64/seelog.xml.template

.PHONY: prepack-linux-386
prepack-linux-386:
	mkdir -p $(GO_SPACE)/bin/prepacked/linux_386
	mkdir -p $(GO_SPACE)/bin/prepacked/linux_386_plugin
	$(COPY) $(GO_SPACE)/bin/linux_386_plugin/session-manager-plugin $(GO_SPACE)/bin/prepacked/linux_386_plugin/session-manager-plugin
	$(COPY) $(GO_SPACE)/bin/seelog_unix.xml $(GO_SPACE)/bin/prepacked/linux_386_plugin/seelog.xml
	$(COPY) $(GO_SPACE)/bin/LICENSE $(GO_SPACE)/bin/prepacked/linux_386_plugin/LICENSE
	$(COPY) $(GO_SPACE)/bin/NOTICE $(GO_SPACE)/bin/prepacked/linux_386_plugin/NOTICE
	$(COPY) $(GO_SPACE)/bin/README.md $(GO_SPACE)/bin/prepacked/linux_386_plugin/README.md
	$(COPY) $(GO_SPACE)/bin/RELEASENOTES.md $(GO_SPACE)/bin/prepacked/linux_386_plugin/RELEASENOTES.md
	$(COPY) $(GO_SPACE)/bin/THIRD-PARTY $(GO_SPACE)/bin/prepacked/linux_386_plugin/THIRD-PARTY
	$(COPY) $(GO_SPACE)/bin/linux_386/ssmcli $(GO_SPACE)/bin/prepacked/linux_386/ssmcli
	$(COPY) $(GO_SPACE)/bin/seelog_unix.xml $(GO_SPACE)/bin/prepacked/linux_386/seelog.xml.template

.PHONY: prepack-linux-arm64
prepack-linux-arm64:
	mkdir -p $(GO_SPACE)/bin/prepacked/linux_arm64_plugin
	$(COPY) $(GO_SPACE)/bin/linux_arm64_plugin/session-manager-plugin $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/session-manager-plugin
	$(COPY) $(GO_SPACE)/bin/seelog_unix.xml $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/seelog.xml
	$(COPY) $(GO_SPACE)/bin/LICENSE $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/LICENSE
	$(COPY) $(GO_SPACE)/bin/NOTICE $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/NOTICE
	$(COPY) $(GO_SPACE)/bin/README.md $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/README.md
	$(COPY) $(GO_SPACE)/bin/RELEASENOTES.md $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/RELEASENOTES.md
	$(COPY) $(GO_SPACE)/bin/THIRD-PARTY $(GO_SPACE)/bin/prepacked/linux_arm64_plugin/THIRD-PARTY

.PHONY: prepack-windows-386
prepack-windows-386:
	mkdir -p $(GO_SPACE)/bin/prepacked/windows_386
	mkdir -p $(GO_SPACE)/bin/prepacked/windows_386_plugin
	$(COPY) $(GO_SPACE)/bin/windows_386_plugin/session-manager-plugin.exe $(GO_SPACE)/bin/prepacked/windows_386_plugin/session-manager-plugin.exe
	$(COPY) $(GO_SPACE)/bin/seelog_windows.xml.template $(GO_SPACE)/bin/prepacked/windows_386_plugin/seelog.xml.template
	$(COPY) $(GO_SPACE)/bin/LICENSE $(GO_SPACE)/bin/prepacked/windows_386_plugin/LICENSE
	$(COPY) $(GO_SPACE)/bin/NOTICE $(GO_SPACE)/bin/prepacked/windows_386_plugin/NOTICE
	$(COPY) $(GO_SPACE)/bin/README.md $(GO_SPACE)/bin/prepacked/windows_386_plugin/README.md
	$(COPY) $(GO_SPACE)/bin/RELEASENOTES.md $(GO_SPACE)/bin/prepacked/windows_386_plugin/RELEASENOTES.md
	$(COPY) $(GO_SPACE)/bin/THIRD-PARTY $(GO_SPACE)/bin/prepacked/windows_386_plugin/THIRD-PARTY
	$(COPY) $(GO_SPACE)/bin/windows_386/ssmcli.exe $(GO_SPACE)/bin/prepacked/windows_386/ssmcli.exe
	$(COPY) $(GO_SPACE)/bin/seelog_windows.xml.template $(GO_SPACE)/bin/prepacked/windows_386/seelog.xml.template

.PHONY: prepack-windows-amd64
prepack-windows-amd64:
	mkdir -p $(GO_SPACE)/bin/prepacked/windows_amd64
	mkdir -p $(GO_SPACE)/bin/prepacked/windows_amd64_plugin
	$(COPY) $(GO_SPACE)/bin/windows_amd64_plugin/session-manager-plugin.exe $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/session-manager-plugin.exe
	$(COPY) $(GO_SPACE)/bin/seelog_windows.xml.template $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/seelog.xml.template
	$(COPY) $(GO_SPACE)/bin/LICENSE $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/LICENSE
	$(COPY) $(GO_SPACE)/bin/NOTICE $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/NOTICE
	$(COPY) $(GO_SPACE)/bin/README.md $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/README.md
	$(COPY) $(GO_SPACE)/bin/RELEASENOTES.md $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/RELEASENOTES.md
	$(COPY) $(GO_SPACE)/bin/THIRD-PARTY $(GO_SPACE)/bin/prepacked/windows_amd64_plugin/THIRD-PARTY
	$(COPY) $(GO_SPACE)/bin/windows_amd64/ssmcli.exe $(GO_SPACE)/bin/prepacked/windows_amd64/ssmcli.exe
	$(COPY) $(GO_SPACE)/bin/seelog_windows.xml.template $(GO_SPACE)/bin/prepacked/windows_amd64/seelog.xml.template

.PHONY: package-rpm-amd64
package-rpm-amd64: create-package-folder
	$(GO_SPACE)/Tools/src/create_rpm_amd64.sh
	$(GO_SPACE)/Tools/src/create_rpm_amd64_plugin.sh

.PHONY: package-rpm-386
package-rpm-386: create-package-folder
	$(GO_SPACE)/Tools/src/create_rpm_386.sh
	$(GO_SPACE)/Tools/src/create_rpm_386_plugin.sh

.PHONY: package-rpm-arm64
package-rpm-arm64: create-package-folder
	$(GO_SPACE)/Tools/src/create_rpm_arm64_plugin.sh

.PHONY: package-deb-amd64
package-deb-amd64: create-package-folder
	$(GO_SPACE)/Tools/src/create_deb_amd64_plugin.sh

.PHONY: package-deb-386
package-deb-386: create-package-folder
	$(GO_SPACE)/Tools/src/create_deb_386_plugin.sh

.PHONY: package-deb-arm
package-deb-arm: create-package-folder
	$(GO_SPACE)/Tools/src/create_deb_arm_plugin.sh

.PHONY: package-deb-arm64
package-deb-arm64: create-package-folder
	$(GO_SPACE)/Tools/src/create_deb_arm64_plugin.sh

.PHONY: package-darwin-arm64
package-darwin-arm64:
	$(GO_SPACE)/Tools/src/create_darwin_arm64_bundle_plugin.sh

.PHONY: package-darwin-amd64
package-darwin-amd64:
	$(GO_SPACE)/Tools/src/create_darwin_amd64_bundle_plugin.sh

.PHONY: package-win-386
package-win-386: create-package-folder
	$(GO_SPACE)/Tools/src/create_win_386_plugin.sh

.PHONY: package-win-amd64
package-win-amd64: create-package-folder
	$(GO_SPACE)/Tools/src/create_win_amd64_plugin.sh

.PHONY: copy-package-dependencies
copy-package-dependencies:
	@echo "Copying packaging dependencies to $(GO_SPACE)/bin/package_dependencies"
	mkdir -p $(GO_SPACE)/bin/package_dependencies

	$(COPY) -r $(GO_SPACE)/Tools $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/packaging $(GO_SPACE)/bin/package_dependencies/

	$(COPY) -r $(GO_SPACE)/seelog_unix.xml $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/seelog_windows.xml.template $(GO_SPACE)/bin/package_dependencies/

	$(COPY) -r $(GO_SPACE)/RELEASENOTES.md $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/LICENSE $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/NOTICE $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/README.md $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/THIRD-PARTY $(GO_SPACE)/bin/package_dependencies/
	$(COPY) -r $(GO_SPACE)/VERSION $(GO_SPACE)/bin/package_dependencies/
