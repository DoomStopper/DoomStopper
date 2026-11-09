# 🛡️ DoomStopper

**The anti-doomscrolling app that Big Tech doesn't want you to have.**

DoomStopper is an Android Accessibility Service that acts as a "pattern interrupt" for YouTube Shorts, TikTok, and Instagram Reels. 

Instead of relying on willpower or basic app blockers (which users just turn off), DoomStopper uses **gradual friction**. It detects when you are swiping through short-form content, drops an invisible shield over the screen, and forces you to wait an increasingly long amount of time before the next video loads.

Eventually, the app becomes so boring that your brain actually *wants* to close it.

## 🚀 How to Install
Because this app actively intercepts and delays the algorithms of major social media apps, it is distributed directly as an APK.

1. Download the latest `DoomStopper.apk` from the **[Releases](#)** tab on the right.
2. Open the file on your Android phone.
3. Your phone may warn you that it is from an "Unknown Source." Click **Allow**.
4. Open the app and grant the Accessibility permission.
5. Open YouTube Shorts and start swiping. 

*Want to verify the APK is safe? Check the [VirusTotal Scan (0/70)](#).*

## 🧠 How it Works (Under the Hood)
DoomStopper doesn't use standard X/Y touch coordinates. It uses `AccessibilityNodeInfo` to deeply inspect the UI tree of target apps. 
* It bypasses the "Ghost Finger Infinite Loop" by utilizing `GestureResultCallback`.
* It handles state management (knowing when you open comments vs Shorts) by scanning for specific `content-desc` tags and `resource-id`s.
* It uses a 300ms Watchdog loop to ensure the shield state is always synced, even if the target app suppresses scroll events.

## 🤝 Contributing
I built this to get my own time back. If you are an Android developer and want to improve the UI, add more target apps, or optimize the Watchdog loop, feel free to submit a Pull Request!
