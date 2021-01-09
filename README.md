What is "Insular"
----------------

"Insular" is a sandbox environment to clone selected apps and isolate them from accessing your personal data outside the sandbox (including call logs, contacts, photos and etc) even if related permissions are granted. Device-bound data (SMS, IMEI and etc) is still accessible.

Isolated app can be frozen on demand, with launcher icon vanish and its background behaviors completely blocked.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    style="height: 80px">](https://f-droid.org/packages/com.oasisfeng.island.fdroid)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-zh-cn.png"
    alt="下载应用，请到 F-Droid"
    style="height: 80px">](https://f-droid.org/packages/com.oasisfeng.island.fdroid)



How does it work
----------------

Insular takes advantage of the "managed profile" feature on Android 5.0+, which is also the base of "Android for Work", to create an isolated sandbox for apps with their data.

App needs to be cloned in Insular first. Afterwards, the clone can run parallel aside from the original one. (even with different accounts signed-in) It can be frozen on demand by Insular. **(NO ROOT REQUIRED)**

If [Greenify](https://play.google.com/store/apps/details?id=com.oasisfeng.greenify) is also installed, apps can be frozen automatically by "Auto-freeze with Greenify" action (in the overflow menu), just like normal app hibernation in Greenify.


Common use cases
----------------

- **Freeze frequently woken apps.** Clone it into Insular and uninstall the original one outside. Then you can freeze it to fully block its background behaviors. Remember to create launch shortcut for quick de-freezing and launching.
- **Prevent permission-hungry apps from accessing your private data.** Sometimes runtime-permission may not be the solution, especially if the app refuses to work without certain permissions. App clones running in Insular cannot access your contacts, call logs and sniff other apps outside. **But SMS and location are exceptions since they are bound to device.**
- **Use two accounts of the same app parallel.** Clone it into Insular and login the other account inside.
- **Archive rarely used apps.** Like the first case, keep them frozen until the next time you need it.
- **Hide your private apps.**
- **Prohibits USB access.** Don't allow the police scan your phone.


Manual setup
------------

On most middle to high end Android devices released after 2016, Insular can be setup straightforward without hassle. But still on some devices, you may got "incompatible with your device" message on Google Play Store, or be notified during the setup with error message "Sorry, your device (or ROM) is incompatible with Insular". In both cases, Insular could still work on your device if setup manually.

If you are prompted to encrypt your device first during the setup and you don't want device decription (which may significantly degrade overall I/O performance on low-end devices), this prerequisite could also be skipped if setup manually.

Please refer to [Manual setup](/setup.md) for prerequisites and detailed steps.


God / Demigod mode
------------------

In normal mode, Island only takes care of apps inside the Island space. The "God / Demigod mode" is an advanced mode, in which Island takes control of **ALL** apps, both inside and **outside** Island space. For example, you can freeze any app without cloning it to Island space. At present God mode is only recommended for advanced users.

The limitations in "God / Demigod mode":

- "App Timer" feature in Digital Wellbeing is not available. (Usage statistics still works)
- If any corporation Google account is logged-in on the device, Google Play Store will operate in "Work Mode" which may block the installation of paid apps.
- On Android version prior to 7.1, app backup (e.g. Cloud backup for app data with Google Drive) will stop working. (Android 7.1+ is not affected)
- (Demigod mode only) You can no longer create new Island space after Demigod is activated. It's suggested to setup Island space before activating Demigod mode.
- (God mode only) This device may no longer be available on the Google Play Store web site. (missing from the drop-down list of devices when initiating app installation from web) App installation from Google Play Store on the device is working as normal.
- (God mode only) You may see "Device is managed by your organization" (or similar words) in some system UI (e.g. Lock Screen). It's a the standard transparency notice of Android for Work. 

God mode could only be [setup manually](/setup.md#manual-setup-for-island-in-god-mode--demigod-mode) at present.


DISCLAIMER
----------

This beta version may be dangerous on some Android devices, it may cause boot-loop and even brick your device. The purpose of closed beta exclusive for advanced users is to widely test and improve the device compatibility. Don't install it on your daily device and remember to BACKUP FIRST.
