# mDNS Finder

A tiny Android app that turns a `.local` (mDNS / Bonjour) name such as `ender3.local` into an IP address, and opens it in the browser with one tap.

Android's built-in resolver does not answer `.local` names, so browsers and apps on many phones cannot reach devices that Windows 10 and Linux find without any trouble. This app sends the mDNS query itself.

- Pure Java, no libraries, no native code, no XML resources (32/64-bit, ARM/x86).
- `minSdk 9` (Android 2.3), `targetSdk 28`.
- Texts in English, or Portuguese when the device language is Portuguese.

## Usage

1. Type the device name (`ender3.local`, or just `ender3`) and press **Find IP address**.
2. The IP address is shown (long-press it to copy on Android 3.0+).
3. Press **Open in browser**.

A port can be typed after the name (`ender3.local:5000`); it is added to the browser address.

The phone must be on the same Wi-Fi network as the device.

## How it works

- One UDP packet to `224.0.0.251:5353` asks for the A and AAAA records of the name, with the "unicast response" bit set, so the answer comes straight back to the app and no multicast group has to be joined.
- The query goes out on every up, multicast-capable interface with an IPv4 address (mobile data is skipped), up to 3 times, 1.5 s each.
- A `MulticastLock` is held while searching, because some Wi-Fi chips drop multicast traffic otherwise.
- IPv4 answers are preferred for the browser button; IPv6 link-local addresses are shown but never opened.

## Build

Requires a JDK, `zip` and the Android SDK command-line tools:

```
sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-28" "build-tools;34.0.0"
ANDROID_HOME=$HOME/android-sdk ./build.sh
```

`ANDROID_JAR=/path/android.jar` and `BUILD_TOOLS=/path/build-tools/34.0.0` can be used instead. The result is `MdnsFinder.apk`, signed with the debug key.

## Tests

The resolver has no Android dependencies, so the tests run on a plain JVM:

```
mkdir -p /tmp/mdns-test
javac -encoding UTF-8 -d /tmp/mdns-test \
    src/br/local/mdnsfinder/Mdns.java src/br/local/mdnsfinder/Msg.java \
    test/br/local/mdnsfinder/TestAll.java
java -cp /tmp/mdns-test br.local.mdnsfinder.TestAll
```

## License

None chosen yet.
