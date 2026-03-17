## Smithy Go

Smithy code generators for Go.

**WARNING: All interfaces are subject to change.**

## Setup
> Note: These steps assume your current working directory is `smithy-go/codegen` (the directory that contains this README)

1. Install Java 17. If you have multiple versions of Java installed on OSX, use `export JAVA_HOME=`/usr/libexec/java_home -v 17``. **Java 14 is not compatible with Grade 5.x**
2. Install Go 1.17 (follow directions for your platform)
3. Use `./gradlew` to automatically install the correct gradle version. **`brew install gradle` will install Gradle 6.x which is not compatible.**
4. `./gradlew test` to run the basic tests.
5. `cd smithy-go-codegen-test; ../gradlew build` to run the codegen tests.

> Note: since gradlew is a script within `smithy-go/codegen`, you need to use an appropriate relative path to access it from within the repo.

## License

This project is licensed under the Apache-2.0 License.

