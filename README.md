# Cockpit UI Driver — Credential Storage Prototype

এই prototype-এ App-এর ভিতরে Cockpit ID/POS, Cockpit Password এবং ERS PIN রাখার UI যোগ করা হয়েছে।

## Credential security

- Android Keystore-এর AES/GCM key ব্যবহার করা হয়েছে।
- Credentials SharedPreferences-এ plaintext হিসেবে রাখা হয় না।
- Password/PIN UI-তে masked থাকে।
- "Credentials নিরাপদভাবে এই ডিভাইসে সংরক্ষণ করুন" বন্ধ করলে নতুন করে credentials save করা হবে না এবং পুরনোগুলো মুছে দেওয়া হবে।
- App uninstall বা app data clear করলে stored credentials হারাবে।
- এটি absolute security guarantee নয়; rooted/compromised device বা malicious accessibility software থেকে নিরাপত্তার নিশ্চয়তা দেওয়া যায় না।

## Automation

1. User Accessibility permission দেয়।
2. Cockpit login screen এলে saved ID/password normal login fields-এ বসানোর চেষ্টা করে।
3. Samsung Pass, biometric, OTP বা অন্য security prompt bypass করে না।
4. Dashboard এ গেলে recharge number/amount automation চালায়।
5. ERS PIN screen এলে saved ERS PIN normal PIN field-এ বসানোর চেষ্টা করে।
6. Confirm button নিজে চাপতে পারে যদি Cockpit accessibility node-এ এটি expose করে; security prompt এলে থেমে যাবে।
7. Success/Failed screen detect করার চেষ্টা করে।

## Important

- Package name `retail.grameenphone.com.gpretail` ধরে নেওয়া হয়েছে।
- Cockpit UI পরিবর্তন হলে accessibility selectors/flow update লাগতে পারে।
- বাস্তব transaction চালানোর আগে test/sandbox বা খুব ছোট authorized recharge দিয়ে পরীক্ষা করুন।
- এটি GP-এর official API বা official integration নয়; UI automation prototype।
- শুধু নিজের/অনুমোদিত Cockpit account-এ ব্যবহার করুন।
