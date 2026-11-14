# AnyAuto Split - Android Auto

Mã nguồn cần biên dịch: `AnyAuto-Split-Build-Ready.zip`.

## GitHub Actions

Workflow: [Build AnyAuto Split APK](../../actions/workflows/anyauto-split.yml).

1. Trên nhánh `anyauto-split-build`, tải `AnyAuto-Split-Build-Ready.zip` vào thư mục `anyauto-split/`, cùng cấp với file README này. **Tải file ZIP nguyên vẹn, không giải nén.**
2. Commit lên nhánh `anyauto-split-build`.
3. Workflow `Build AnyAuto Split APK` chạy tự động. Nếu build thành công, tải artifact `AnyAuto-Split-APK`, giải nén để lấy `AnyAuto-Split.apk`.

SHA-256 của bản ZIP đã kiểm tra:
`e5d025418a34572932753128c2c8de25150596874a72ee198f3c96c6a650c19e`

Workflow từ chối những file không trùng SHA-256 để tránh vô tình build sai phiên bản. Đối với bản ZIP nguồn khác, cập nhật giá trị SHA-256 trong workflow cho phù hợp.

Bản này chưa được build và test trên Android Auto thực tế. Việc tạo APK thành công không đảm bảo Android Auto sẽ hiển thị ứng dụng, hoặc tính năng chia màn hình hoạt động trên mọi xe.

Lưu ý: không tự ý cài và sử dụng video trên màn hình ô tô khi đang lái xe.
