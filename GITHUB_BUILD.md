# GitHub থেকে APK Build

1. GitHub-এ একটি নতুন **private repository** তৈরি করুন।
2. এই project-এর সব file repository-তে upload করুন।
3. `.github/workflows/android-build.yml` ফাইলটি অবশ্যই রাখতে হবে।
4. GitHub-এর **Actions** tab খুলুন।
5. **Build Cockpit UI Driver APK** workflow নির্বাচন করুন।
6. **Run workflow** চাপুন।
7. Build শেষ হলে workflow-এর নিচে **Artifacts** থেকে `Cockpit-UI-Driver-debug-apk` download করুন।
8. ZIP খুললে `app-debug.apk` পাবেন।

এই workflow কোনো signing key ব্যবহার করে না; এটি install/test করার জন্য debug APK বানাবে। Play Store বা production release-এর জন্য আলাদা signing key প্রয়োজন।
