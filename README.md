# mDNS Finder

A tiny Android app that turns a `.local` (mDNS / Bonjour) name into an IP address, and opens it in the browser with one tap.

Android's built-in resolver does not answer `.local` names, so browsers and apps on many phones cannot reach devices that Windows 10+, Desktop Linux and Apple devices find without any trouble. This app sends the mDNS query itself.

- Pure Java, no libraries, no native code, no XML resources (32/64-bit, ARM/x86).
- Small APK; runs on Android 2.3 (API 9) and newer.
- Supports both English and Portuguese.

![App demo image](https://github.com/diogodiogo2/android-mdns-finder/blob/main/mdns-finder-demo.png?raw=true)

## AI Disclaimer

This whole project (and by that I mean everything, even this readme) has been written by AI, more specifically Claude. I don't even know how to program in Java.

It's just a simple app I've built for personal use, and decided to share as I quite liked the result. It hasn't been extensively tested and I may not be able to help with all the issues (feel free to document them though, I'll do my best).

I understand many people don't like AI code, and I get their reasoning. You're completely free to avoid using this project. It's just a quick project I wanted to share.

While I'm quite certain this can't do any harm to your Android device (it doesn't even request permissions and the IP addresses shown are local, meaning it's useless to anyone outside your local network), the program comes with NO WARRANTY whatsoever. You should agree to take any risks associated with it.

## Usage

1. Type the device name (`DeviceName.local`, or just `DeviceName`) and press **Find IP address**.
2. The IP address is shown (long-press it to copy on Android 3.0+).
3. Press **Open in browser** if you want.

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
