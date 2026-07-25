# Read Me First
The following was discovered as part of building this project:

* The original package name 'com.example.media-extractor' is invalid and this project uses 'com.example.mediaextractor' instead.

# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/3.5.3/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/3.5.3/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

### Use below command to run the application

```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar [<sourceDir>] [--consolidate]
```

**Arguments:**
- `<sourceDir>` (optional): Source directory containing media and archives. Defaults to `C:\Users\Shailender\projects\backup`
- `--consolidate` (optional): Enable consolidation phase to organize extracted media into flat consolidated directories sorted by date

**Output Directories:**

Without consolidation:
- `target/photos/` - extracted photos from source
- `target/videos/` - extracted videos from source

With `--consolidate` flag (additional):
- `target/consolidated/photos/` - consolidated photos sorted by modification date (ascending)
- `target/consolidated/videos/` - consolidated videos sorted by modification date (ascending)

The consolidation phase copies all extracted media into flat consolidated directories, maintaining original filenames and processing files in date-ascending order. Filename collisions are handled by appending a numeric suffix.

### Examples

Extract media only (default):
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar
```

Extract from custom source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar C:\Users\Shailender\projects\backup
```

Extract and consolidate by date:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar C:\Users\Shailender\projects\backup --consolidate
```

Consolidate with default source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar --consolidate
```
