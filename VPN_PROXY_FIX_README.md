# 🔧 VPN Proxy Connection Fix - Installation Guide

## مشکل اصلی
پروکسی/VPN در حالت "Connecting" گیر می‌کند و اپ‌لیکیشن هنگ می‌کند.

## ✅ راه‌حل
- **Timeout 15 ثانیه‌ای** برای تلاش اتصال پروکسی
- **خودکار Fallback** به اتصال مستقیم در صورت ناموفق
- **Retry Logic** با exponential backoff (حداکثر 3 تلاش)
- **خودکار بازیابی** هنگام راه‌اندازی دوباره اپ

---

## 📥 نصب

### گزینه 1: استفاده از APK Pre-built
دانلود فایل APK از [Releases](https://github.com/HELBOYCODER/entinyGram/releases)

```bash
adb install -r entinyGram-vpn-fix.apk
```

### گزینه 2: ساختن از کد منبع

**پیش‌نیازها:**
- JDK 21
- Android SDK 36
- Bun
- Git & StGit

**مراحل:**

```bash
# 1. کلون کردن repository
git clone https://github.com/HELBOYCODER/entinyGram.git
cd entinyGram

# 2. استفاده از fix branch
git checkout fix/vpn-connecting-hang

# 3. ساخت APK (Debug)
chmod +x build-vpn-fix.sh
./build-vpn-fix.sh debug

# یا Release build
./build-vpn-fix.sh release
```

**APK Output:**
```
worktree/TMessagesProj_App/build/outputs/apk/debug/app.apk
```

---

## 🔍 تشخیص مشکل

### اگر VPN همچنان هنگ کند:

1. **Diagnostic Config را enable کنید:**
```bash
# فایل vpn-proxy-hang.entinylog را به Telegram ارسال کنید
# Settings → entinyGram → Additional → Diagnostics → Send logs
```

2. **Logcat را بررسی کنید:**
```bash
adb logcat | grep -i "proxy\|connecting"
```

3. **Expected Log Output:**
```
Proxy connection timeout after 15000ms
Falling back to direct connection
Proxy timeout detected for: [proxy-address]
```

---

## ⚙️ تنظیمات

### خودکار Fallback
- اگر پروکسی 3 بار ناموفق باشد → اتصال مستقیم استفاده شود
- Toast message نمایش داده می‌شود

### Manual Recovery
اگر مشکل ادامه داشت:
1. Settings → Network & Proxy
2. Proxy را Disable کنید
3. دوباره Enable کنید
4. "Ping All Proxies" را تاپ کنید

---

## 📝 لاگ‌ها

لاگ‌های تشخیصی در اینجا ذخیره می‌شوند:
```
/sdcard/Android/data/ua.entaytion.entinygram/files/logs/
```

---

## 🐛 Debug اطلاعات

### Patch فایل
```
patches/bugfix/proxy-connection-timeout.patch
```

### تغییرات اصلی
```java
// 15-second timeout
private static final int PROXY_CONNECT_TIMEOUT_MILLIS = 15000;

// Max 3 retry attempts
private static final int PROXY_RETRY_MAX_ATTEMPTS = 3;

// Auto-fallback on failure
private void fallbackToDirectConnection()
```

---

## 📞 Support

مشکل گزارش کنید:
- GitHub Issues: https://github.com/HELBOYCODER/entinyGram/issues
- Telegram: @entinyGram

---

**Version:** 1.0
**Released:** 2026-09-29
**Fixed in:** Commit 5db64562bf00d2917bf9430e974622db248fc960
