## 1. 目标
删除operator和regulator目录下重复的登录和开屏Activity源代码及布局文件，修复common目录下登录界面的深色主题背景色。

## 2. 实施
- [ ] 2.1 删除 `app/src/main/java/com/heyu/safetybelt/operator/activity/LoginActivity.kt`
- [ ] 2.2 删除 `app/src/main/java/com/heyu/safetybelt/operator/activity/SplashActivity.kt`
- [ ] 2.3 删除 `app/src/main/res/layout/activity_login_worker.xml`
- [ ] 2.4 删除 `app/src/main/res/layout/activity_splash_worker.xml`
- [ ] 2.5 删除 `app/src/main/java/com/heyu/safetybelt/regulator/activity/LoginActivity.kt`
- [ ] 2.6 删除 `app/src/main/java/com/heyu/safetybelt/regulator/activity/SplashActivity.kt`
- [ ] 2.7 删除 `app/src/main/res/layout/activity_login_regulator.xml`
- [ ] 2.8 删除 `app/src/main/res/layout/activity_splash_regulator.xml`
- [ ] 2.9 [`app/src/main/res/layout/activity_login.xml`](app/src/main/res/layout/activity_login.xml)，移除 `android:background="@android:color/white"` 属性。