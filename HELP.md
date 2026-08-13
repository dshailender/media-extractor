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
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar [<sourceDir>]
```

**Arguments:**
- `<sourceDir>` (optional): Source directory containing media and archives. Defaults to `C:\Users\Shailender\projects\backup`

**Output Directory Structure:**

Media files are extracted and organized by year in the user's home directory:
```
~/memories/
  ├── 2023/
  │   ├── photos/
  │   │   ├── photo1.jpg
  │   │   ├── photo2.png
  │   │   └── ...
  │   └── videos/
  │       ├── video1.mp4
  │       └── ...
  ├── 2024/
  │   ├── photos/
  │   │   └── ...
  │   └── videos/
  │       └── ...
  └── 2025/
      ├── photos/
      └── videos/
```

**Key Features:**
- Photos extracted to: `~/memories/{YYYY}/photos/`
- Videos extracted to: `~/memories/{YYYY}/videos/`
- Year ({YYYY}) is determined by the file's last modified time
- All files stored in a flat structure (no subdirectories created for source folder hierarchy)
- Filename collisions handled by appending numeric suffix (e.g., `photo_1.jpg`, `photo_2.jpg`)
- Supports nested archives: ZIP, TAR, TAR.GZ, TAR.BZ2
- Single-phase extraction (no separate consolidation step needed)

### Examples

Extract media with default source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar
```

Extract from custom source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar /home/user/MyPhotos
```

On Windows:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar C:\Users\YourName\Pictures
```
