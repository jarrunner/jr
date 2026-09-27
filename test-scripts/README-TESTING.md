# Testing Guide for Java Runner (jr)

## Quick Start

1. **Build test JARs:**
   ```batch
   build-test-jar.bat
   ```

2. **Run comprehensive tests:**
   ```batch
   TestJR.bat
   ```

## Manual Testing

### Test 1: Traditional Mode (No Config)

```batch
cd ..
jr.exe test-scripts\TestStartupTiming.jar
```

### Test 2: Config Mode - Basic

```batch
cd ..
# Copy jr.exe with a new name
copy jr.exe testapp.exe

# Copy an example config
copy test-scripts\example-basic.jrc testapp.jrc

# Edit testapp.jrc if needed, then run
testapp.exe
```

### Test 3: Config Mode - Full Features

```batch
cd ..
copy jr.exe myapp.exe
copy test-scripts\example-full.jrc myapp.jrc

# Edit myapp.jrc to customize settings
# Then run
myapp.exe

# Check the log file
type myapp.log
```

### Test 4: Create Config Automatically

```batch
cd ..
jr.exe -Xjr:create-config=test-scripts\TestStartupTiming.jar

# This creates jr.jrc - edit and test
jr.exe
```

## Example Config Files

- **example-basic.jrc** - Minimal configuration
- **example-full.jrc** - All options demonstrated
- **example-classpath.jrc** - Using -cp instead of -jar

## AOT Testing

AOT cache is enabled by default. To test:

1. **First run** (creates cache):
   ```batch
   testapp.exe
   # Look for: "Creating new AOT cache"
   # Cache file: testapp.*.aot created
   ```

2. **Second run** (reuses cache):
   ```batch
   testapp.exe
   # Should be faster, no cache creation message
   ```

3. **Modify JAR** (invalidates cache):
   ```batch
   touch testapp.jar
   testapp.exe
   # Creates new cache, old one auto-deleted
   ```

## Clean Up Test Files

```batch
cd ..
del testapp.* myapp.* *.aot *.log
```

## Build Both Executables

```batch
cd ..
build-win.bat
```

This creates:
- `jr.exe` (23KB, requires VC++ Redistributable)
- `jr-standalone.exe` (193KB, no dependencies)

Both work identically. Use `jr-standalone.exe` for distribution.
