# XimiFarming MobileGIS - Ứng dụng Quản lý Lô đất & Theo dõi Cây trồng

Dự án này là một ứng dụng di động GIS được xây dựng bằng **Java** trong **Android Studio**, tích hợp bản đồ số vệ tinh và xử lý dữ liệu nông nghiệp thông minh. Ứng dụng cho phép chia lô đất, định vị cây trồng, phân tích chỉ số thực vật vệ tinh qua **Google Earth Engine (GEE)** và chỉ số độ xanh lá cây **Excess Green Index (ExG)** cục bộ qua ảnh chụp camera.

---

## Các Tính năng Chính

1. **Bản đồ GIS & Đa giác Lô đất (Polygons)**:
   - Vẽ và quản lý ranh giới lô đất trực tiếp trên bản đồ Google Maps.
   - Tính toán diện tích lô đất chính xác theo m² trên mặt cầu WGS84.
   - Đánh giá phân loại màu sắc ranh giới dựa trên tình trạng sức khỏe thực vật (Tốt: Xanh, Cảnh báo: Cam, Nguy cơ: Đỏ).
2. **Định vị & Theo dõi Cây trồng (Markers)**:
   - Ghim vị trí cây trồng đơn lẻ trên bản đồ.
   - Quản lý nhật ký chăm sóc, diễn tiến sinh trưởng riêng cho từng cây.
3. **Tích hợp Google Earth Engine (GEE) REST API**:
   - Tự động lấy token OAuth2 thông qua tệp xác thực Service Account.
   - Gửi yêu cầu phân tích dữ liệu Sentinel-2 để tính toán chỉ số NDVI.
   - Nạp động lớp phủ ảnh vệ tinh NDVI đè lên bản đồ lô đất trong thời gian thực.
4. **Phân tích Sức khỏe Lá cây Ngoại tuyến (ExG Heatmap)**:
   - Người dùng có thể chụp ảnh lá cây bằng camera hoặc chọn từ Thư viện.
   - Sử dụng thuật toán xử lý ảnh Bitmap để tính chỉ số Excess Green Index (ExG = 2*G - R - B).
   - Xuất ảnh Heatmap phân tích độ xanh lá cây (các điểm thiếu diệp lục/vàng lá hiển thị đỏ, lá xanh đậm hiển thị xanh lá sáng) để cảnh báo thiếu dinh dưỡng hoặc sâu bệnh.
5. **Cơ sở dữ liệu Room Database (Offline)**:
   - Lưu trữ toàn bộ dữ liệu ngoại tuyến trên SQLite nội bộ thông qua thư viện Room.

---

## Hướng dẫn Thiết lập Dự án

### 1. Cấu hình Khóa API Google Maps
Để bản đồ Google Maps hiển thị bình thường, bạn cần cấu hình Maps API Key:
1. Truy cập [Google Cloud Console](https://console.cloud.google.com/) và kích hoạt **Maps SDK for Android**.
2. Tạo một **API Key**.
3. Mở tệp [local.properties](file:///c:/Users/admin/Desktop/mobileGIS/local.properties) của dự án và dán API key của bạn vào:
   ```properties
   MAPS_API_KEY=AIzaSy...YourActualApiKeyHere...
   ```
*(Lưu ý: Tệp `local.properties` đã được thêm vào `.gitignore` để tránh bị lộ khóa API lên Git).*

### 2. Thiết lập Google Earth Engine (GEE)
Ứng dụng sử dụng Google Service Account để xác thực API Earth Engine mà không cần người dùng cuối đăng nhập tài khoản Google của họ.
1. Truy cập trang quản lý Google Cloud của bạn, đăng ký sử dụng **Google Earth Engine API**.
2. Tạo một **Service Account** (Tài khoản Dịch vụ) mới.
3. Cấp vai trò **Earth Engine Resource Viewer** (hoặc Earth Engine Admin) cho Service Account này.
4. Tạo và tải xuống Khóa tài khoản dịch vụ dưới dạng tệp **JSON** (private key).
5. Đổi tên tệp JSON đó thành `service_account_key.json` và lưu vào thư mục:
   `app/src/main/assets/service_account_key.json`

> [!TIP]
> **Chế độ GEE Giả lập (Offline Mock)**:
> Nếu bạn chưa cấu hình Service Account hoặc đang kiểm thử ngoại tuyến, ứng dụng sẽ **tự động chuyển sang chế độ Mock GEE**. Bản đồ sẽ tự động vẽ lớp phủ nhiệt NDVI giả lập ngay trên các đa giác lô đất đã chia của bạn, giúp bạn trải nghiệm đầy đủ giao diện GIS mà không cần kết nối API.

---



## Yêu cầu Hệ thống & Biên dịch

- **Android Studio**: Phiên bản Giraffe (2022.3.1) trở lên.
- **JDK**: Java 17.
- **Android Target SDK**: API 34.
- **Android Min SDK**: API 26 (Android 8.0 trở lên).
