# AA Mirror v0.3.9 — Hybrid Accessibility

Chạy app điện thoại trên màn hình Android Auto, chia tỉ lệ 30-70 / 50-50 / 70-30, chạm được.
Android 12 trở lên (minSdk 31). Cần **Shizuku**.

## Build
1. Cài Android Studio (Ladybug trở lên). Mở thư mục này → để Studio sync Gradle (cần mạng).
2. Build → Build APK(s). Nếu máy đã có Gradle 8.9, có thể chạy `gradle :app:assembleDebug`.
3. Cài APK: `app/build/outputs/apk/debug/app-debug.apk`.

Repo có GitHub Actions tự build APK debug-signed sau mỗi lần push lên `main`. APK này cài trực tiếp để test/cập nhật nội bộ. Bản release production cần keystore riêng và không nên commit private key vào repo.

## Cài lên Android Auto (KingInstaller)
Android Auto thường ẩn app cài ngoài Store. Cài APK bằng KingInstaller (github.com/fcaronte/KingInstaller):
- Android 15/16/17: dùng chế độ **Hybrid Shizuku** (hoặc root).
- Android 12-14: chế độ thường.
- KHÔNG dùng `adb install` / cài thẳng bằng Shizuku (installer = com.android.shell, Android Auto sẽ bỏ qua).
- Bật Android Auto → Developer settings → **Unknown sources**. Nếu vẫn không thấy app: AA-Tweaker / XLauncher Unlocked.

## Dùng
1. Mở Shizuku, khởi động (Wireless debugging hoặc root). Sau mỗi lần reboot (không root) phải khởi động lại.
2. Mở AA Mirror → bấm nút Shizuku để cấp quyền → trạng thái "Sẵn sàng ✔".
3. Chọn app A, app B, tỉ lệ, DPI.
4. Bấm "Xem thử trên điện thoại" để kiểm tra trước (cảm ứng đầy đủ).
5. Để **tự bật bàn phím**, bấm "Tự bật bàn phím khi chạm ô nhập" rồi bật dịch vụ **AA Mirror** trong Trợ năng.
6. Cắm xe / chạy Desktop Head Unit → mở "AA Mirror" trong danh sách app của Android Auto.
   Thanh nút: đổi tỉ lệ | đổi chỗ A⇄B | Back | bàn phím thủ công ; nút tải lại nằm ở dải nút bản đồ.

## Kiến trúc
- `MirrorEngine`: mỗi app = 1 VirtualDisplay (public + own-content-only) ghi vào ImageReader (HardwareBuffer); ghép lên Surface đích theo tỉ lệ.
- `ShellService` (quyền shell qua Shizuku): `am start --display N` để mở app lên display, `IInputManager.injectInputEvent` + `setDisplayId` để bơm cảm ứng/phím (dự phòng: `input -d N`).
- `car/MirrorCar.kt`: CarAppService + NavigationTemplate, nhận Surface và sự kiện chạm.

## Chọn app ngay trên Android Auto (0.3.8)
- Toolbar có thêm nút **Apps** (biểu tượng 4 ô).
- Chọn App A hoặc App B ngay trên màn hình xe.
- Danh sách app dùng SearchTemplate, hỗ trợ tìm theo tên/package và không tự bật bàn phím khi vừa mở.

## Toolbar giữa phía trên
- Toàn bộ control (bao gồm đổi tỉ lệ) nằm trong toolbar AA Mirror tự vẽ trên Surface.
- Không còn nút ActionStrip template-scoped của Android Auto. Engine tạo một ActionStrip rỗng qua constructor serializer nội bộ để thỏa điều kiện của NavigationTemplate mà host không có nút nào để vẽ.
- Map PAN vẫn được giữ vì Android Auto yêu cầu PAN trong map action strip để chuyển gesture scroll/swipe tới SurfaceCallback.
- Hai app dùng toàn bộ `SurfaceContainer` thay vì `stableArea`, nên không còn khoảng đen lớn quanh nội dung. Các nút Android Auto được vẽ overlay phía trên như app navigation.
- Bỏ toàn bộ viền trắng/xanh của pane; focus A/B vẫn tồn tại nội bộ để route swipe/Back/keyboard.
- Khoảng cách giữa hai pane = 0 px.

## Cảm ứng A/B (0.2)
- **Tap** trên Android Auto có tọa độ nên được route thẳng vào đúng app A hoặc B.
- **Swipe/scroll** của Android Auto projected chỉ gửi `distanceX/distanceY`, không gửi tọa độ `ACTION_DOWN`. Vì vậy app dùng mô hình **active pane**: chạm pane nào thì pane đó có viền xanh và nhận Back / bàn phím / swipe tiếp theo.
- Một gesture đã bắt đầu ở pane nào sẽ **khóa cứng vào pane đó cho tới UP**, không thể nhảy sang app còn lại giữa chừng.
- Mỗi pane có điểm chạm cuối riêng, nên A/B không dùng lẫn tọa độ anchor của nhau.
- Preview trên điện thoại có raw `DOWN/MOVE/UP`, nên kéo trực tiếp ở pane nào là pane đó nhận gesture, kể cả kéo qua đường chia.

## Bàn phím tự động
- Android không cho IME/Gboard của điện thoại vẽ trực tiếp lên **VirtualDisplay do app tạo** vì giới hạn bảo mật. Vì vậy AA Mirror dùng **SearchTemplate của Android Auto** để hiện bàn phím native của head unit.
- Khi người dùng tap vào một `EditText`/ô tìm kiếm trong app đang mirror, `MirrorAccessibilityService` vừa nghe Accessibility event vừa **chủ động probe input focus sau ACTION_UP**.
- Probe dùng `getWindowsOnAllDisplays()` thay vì `getWindows()`.
- Fallback Shizuku chỉ chấp nhận các dòng `EditorInfo` (`mCurAttribute`/`mCurrentTextBoxAttribute`) và đúng package. Không còn dùng `mCurFocusedWindow`/`packageName` chung, nên tap vào view bình thường sẽ không bật keyboard.
- Event chỉ được chấp nhận nếu đúng package/display và có tap từ Android Auto trong ~2.5 giây trước đó, tránh bật nhầm từ màn hình điện thoại.
- `KeyboardBridge` yêu cầu `MirrorScreen` push `InputScreen`; keyboard Android Auto hiện ngay.
- Khi SearchTemplate làm Android Auto thu hồi Surface tạm thời, engine **giữ VirtualDisplay sống**; sau khi Submit/Back, Surface được attach lại nên app không bị restart.
- Nếu ô đã có chữ, nội dung hiện tại được đưa vào SearchTemplate; khi Enter, engine dùng `Ctrl+A -> Delete -> input text/paste -> Enter` trên **đúng display**.
- Ô password bị bỏ qua. Nếu app dùng custom view không phát Accessibility event, dùng nút keyboard thủ công trên thanh Android Auto.

## Chống nhân bản app / giảm nóng máy (0.3.6)
- Bỏ `FLAG_ACTIVITY_MULTIPLE_TASK`; launcher dùng `NEW_TASK | REORDER_TO_FRONT` để ưu tiên reuse activity/task trên VirtualDisplay.
- Không fallback `Context.startActivity()` khi Shizuku launch thất bại, tránh ROM mở nhầm app trên display 0 của điện thoại.
- Khi Surface/session Android Auto bị hủy, engine **không release VirtualDisplay**. Nó chỉ `setSurface(null)`, giữ displayId/task nhưng dừng render.
- Khi Android Auto kết nối lại, VirtualDisplay cũ được resume nên không cần tạo thêm bản app.
- Nút Refresh là soft refresh, không phá display.
- Hai nguồn frame được coalesce về tối đa khoảng **30 fps tổng**, giảm copy HardwareBuffer/GPU load và nhiệt.

## Icon + cập nhật (0.3.8)
- Có launcher/adaptive icon riêng cho AA Mirror.
- Main build từ v0.3.8 dùng signing key được giữ trong GitHub Actions cache để các bản sau có thể cài đè.
- **v0.3.8 là mốc signing mới**: từ v0.3.7 hoặc cũ hơn có thể phải gỡ bản cũ đúng một lần.
- Mỗi push main tự tạo GitHub Release kèm APK.
- App kiểm tra GitHub Releases khi mở và WorkManager kiểm tra mỗi 24 giờ; có bản mới sẽ hiện dialog/notification (Android 13+ cần quyền thông báo).

## Hybrid Accessibility (0.3.9)
- Nếu Shizuku sẵn sàng: giữ raw input đầy đủ như trước.
- Nếu không có Shizuku nhưng AA Mirror Accessibility đang bật: launcher thử mở app lên VirtualDisplay bằng `ActivityOptions.setLaunchDisplayId()` + display context.
- Tap được chuyển thành `AccessibilityNodeInfo.ACTION_CLICK` trên node chứa đúng tọa độ.
- Swipe được gom theo gesture và chuyển thành action scroll trên container của đúng package/display.
- SearchTemplate submit dùng `ACTION_SET_TEXT` và thử `ACTION_IME_ENTER`.
- Back dùng Accessibility global Back chỉ khi package đích thực sự có window trên VirtualDisplay.
- Giới hạn: canvas/game/custom gesture, kéo Maps, scrub video, slider và thao tác tọa độ tùy ý vẫn cần Shizuku/root. Một số ROM có thể không cho app thường launch activity của app khác lên VirtualDisplay; khi đó Accessibility-only sẽ không mở được app.

## Giới hạn đã biết
- Build CI được tự động kiểm tra trên GitHub Actions; vẫn cần test thực tế trên head unit/ROM cụ thể, nhất là Android 16/17.
- Với **Android Auto projected**, API công khai không cung cấp tọa độ bắt đầu của thao tác scroll. Vì vậy không thể biết một cú vuốt trực tiếp bắt đầu ở A hay B nếu trước đó chưa chọn pane; hãy chạm pane cần điều khiển trước khi vuốt.
- Không có đa điểm (pinch) trong engine hiện tại. Màn hình Xem thử trên điện thoại thì cảm ứng đầy đủ (1 ngón).
- IME/Gboard không thể vẽ trực tiếp trên VirtualDisplay app-owned; 0.3 dùng keyboard native của Android Auto qua SearchTemplate.
- App có FLAG_SECURE hoặc không hỗ trợ display phụ sẽ đen/không mở.
- Xe có thể khoá video/cảm ứng khi đang chạy.
