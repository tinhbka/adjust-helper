# Cập nhật phía Flutter

Native đã đổi cách phân loại user thành **paid / free**. Phía Dart chỉ cần sửa 3 chỗ dưới đây.

## 1. `initSdk`: bỏ 2 tham số

```diff
 await _channel.invokeMethod('initSdk', {
   'environment': env,
   'appToken': appToken,
-  'apiToken': apiToken,
-  'fullAdsOption': {...},
   'impressionToken': impressionToken,
   'event80Token': event80Token,
   'iapOptions': {...},
+  // Tuỳ chọn, bỏ qua thì dùng mặc định:
+  // 'trustAfterMs': 8000,
+  // 'waitMaxMs': 20000,
+  // 'option1TimeoutMs': 10000,
 });
```

Nếu chưa kịp xoá `apiToken` và `fullAdsOption` thì cũng không sao: native sẽ bỏ qua hai tham số này.

## 2. Callback: đổi tên method và các key

```diff
 _channel.setMethodCallHandler((call) async {
   switch (call.method) {
-    case 'onFullAdCallback':
-      final isFullAds = call.arguments['isFullAds'] as bool;
-      final network = call.arguments['network'] as String?;
-      // fromCache, fromLib, fromApi, fromReferrer
+    case 'onPaidCallback':
+      final isPaid = call.arguments['isPaid'] as bool;
+      final isFinal = call.arguments['isFinal'] as bool;
+      final done = call.arguments['done'] as bool;
+      final network = call.arguments['network'] as String?;
+      final json = call.arguments['json'] as String;
+      _onPaid(isPaid, isFinal, done, network, json);
   }
 });
```

Các key cũ `isFullAds`, `fromCache`, `fromLib`, `fromApi` và `fromReferrer` không còn nữa. Muốn biết thông tin tương ứng thì đọc trong `json` (xem mục 4).

**Callback có thể được gọi nhiều lần trong một phiên:**

| Lần gọi | Khi nào |
|---|---|
| `event = cache` | Không phải lần mở đầu tiên. Kết quả đã có sẵn, callback bắn ngay khi init |
| `event = decide` | Lần mở đầu tiên có kết quả. Khoảng 97% user nhận được ở giây ~2 |
| `event = upgrade` | Đang free thì chuyển sang paid (nguồn thứ hai về muộn). Có thể xảy ra ở lần mở sau |
| `event = timeout` | Hết thời gian chờ mà vẫn free, chưa chắc chắn |
| `event = info` | Kết quả không đổi, chỉ có thêm dữ liệu (network, tracker…) để log |

Nên dùng như sau:
- Muốn quyết định **nhanh nhất**: dùng `isPaid` ngay ở lần callback đầu tiên. `paid` đã có thì không bao giờ đổi ngược lại.
- Muốn quyết định **chắc chắn**: chờ đến khi `done == true`, tức là đã final, đã hết thời gian chờ, hoặc không phải lần mở đầu.
- Dù chọn cách nào thì vẫn phải xử lý trường hợp `isPaid` chuyển từ `false` sang `true` ở những lần callback sau.

## 3. `getInstallReferrer` → `getPaidResult`

```diff
-final info = await _channel.invokeMethod('getInstallReferrer');
+final r = await _channel.invokeMethod('getPaidResult');
+// null nếu chưa init; nếu không thì là map giống hệt args của onPaidCallback
```

## 4. Log lên Firestore

`json` là một chuỗi JSON. Chỉ cần decode rồi ghi thẳng lên Firestore. Nên dùng `merge` với doc id cố định theo từng thiết bị: callback gọi nhiều lần cũng chỉ cập nhật cùng một doc.

```dart
void _onPaid(bool isPaid, bool isFinal, bool done, String? network, String json) {
  final data = jsonDecode(json) as Map<String, dynamic>;
  FirebaseFirestore.instance
      .collection('install_source')
      .doc(deviceId) // vd adid lấy từ getAdid
      .set({...data, 'updatedAt': FieldValue.serverTimestamp()}, SetOptions(merge: true));
}
```

Ví dụ nội dung `json`:

```json
{
  "result": "paid",
  "isPaid": true,
  "isFinal": true,
  "done": true,
  "certainty": "final",
  "decidedBy": "option2",
  "reason": "option2_network",
  "detail": "Google Ads ACI",
  "network": "Google Ads ACI",
  "decidedAtMs": 9300,
  "appVersion": "1.0.9+109",
  "firstRun": true,
  "event": "upgrade",
  "option1": {
    "raw": "utm_source=google-play&utm_medium=...",
    "clickTs": 0, "installTs": 1727000000, "instant": false,
    "error": null, "source": "google-play", "medium": "...",
    "campaign": null, "clickKey": null, "atMs": 2100
  },
  "option2": {
    "network": "Google Ads ACI", "tracker": "Google Ads ACI::Campaign",
    "campaign": "...", "adgroup": "...", "creative": "...", "atMs": 9300
  }
}
```

### Ý nghĩa các field

| Field | Giá trị |
|---|---|
| `result` | `paid` / `free` / `unknown` |
| `certainty` | `final` (chắc chắn) / `provisional` (tạm thời) / `none` |
| `decidedBy` | `option1` / `option2` / `timeout` / `legacy` (chuyển từ cache của phiên bản cũ) |
| `reason` + `detail` | Lý do ra kết quả, xem bảng dưới |
| `network` | Network do option 2 trả về (null nếu chưa có) |
| `decidedAtMs` | Số ms từ lúc init đến lúc quyết định. null nếu quyết định ở lần mở sau |
| `option1.atMs`, `option2.atMs` | Số ms từ lúc init đến lúc mỗi nguồn trả kết quả ở lần mở đầu |
| `firstRun` | `true` nếu đây là lần mở app đầu tiên (chưa có cache) |

### Mã `reason`

| `reason` | Kết quả | `detail` |
|---|---|---|
| `option1_key` | paid, final | Tên click-id (`gclid`, `gbraid`, `adjust_external_click_id`…) |
| `option1_source` | paid, final | Giá trị `utm_source` |
| `option1_medium` | paid, final | Giá trị `utm_medium` |
| `option1_click` | paid, final | Click timestamp (có click nhưng chuỗi bị cắt) |
| `option2_network` | paid, final | Tên network |
| `option1_free` | free, provisional | `empty` / `not_set` / `store` / `error:...` / `unknown_params` |
| `both_free` | free, final | Tên network |
| `timeout` | free, provisional | Giống `option1_free` |
| `legacy` | paid (final) hoặc free (provisional) | Network lưu từ phiên bản cũ |

### Kiểm tra sau một tuần (theo spec)
- Tỉ lệ doc có `decidedBy = timeout` hoặc `reason = option1_free` mà sau đó có `event = upgrade`. Nếu tỉ lệ này **trên ~5%** thì tăng `trustAfterMs` hoặc `waitMaxMs`.
- Phân bố `option2.atMs` khi `result = paid` và `decidedBy = option2`, dùng để chỉnh lại `trustAfterMs`.
