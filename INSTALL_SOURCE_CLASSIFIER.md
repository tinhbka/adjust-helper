# Install Source Classifier — Organic (Play Store) vs Paid (Campaign)

Mục tiêu: xác định **sớm nhất có thể** user cài app từ Play Store tự nhiên hay qua campaign, bằng cách kết hợp **Google Play Install Referrer** (nhanh, ~2s) và **Adjust attribution** (chính xác hơn, ~9s).

Tài liệu này là spec để implement trong `lib/`. Số liệu tham chiếu lấy từ build 109 của `com.money.manager.expense.tracker.budget` (2.915 user, 21–25/09/2026).

---

## 1. Kết luận từ data

| Bucket | Rows | Referrer | Adjust | Ý nghĩa |
|---|---|---|---|---|
| both_full | 2.289 | có gclid / click id | Google Ads ACI, TikTok SAN | Paid, 2 nguồn khớp |
| referrer | 542 | có gclid / click id | **Organic** | Paid, Adjust chưa kịp match (timing) |
| adjust | 49 | `utm_medium=organic` | Google Ads ACI, TikTok SAN | Paid, referrer không thấy (SAN / view-through) |
| both_normal | 26 | `utm_medium=organic` | Organic | Organic thật |

Timing (giây kể từ khi app mở, đo tại SplashScreen):

| | Referrer p50 / p90 | Adjust p50 / p90 |
|---|---|---|
| both_full (attribution thật) | 2 / 7 | **9 / 17** |
| referrer (Adjust trả Organic) | 2 / 6 | **4 / 9** |
| adjust | 3 / 8 | 9 / 16 |
| both_normal | 3 / 7 | 5 / 12 |

Ba điều rút ra:

1. **Referrer có click id ⇒ paid, chắc chắn.** 2.831/2.831 trường hợp Adjust đều xác nhận. Quyết định được ở ~2s, không cần đợi Adjust.
2. **Adjust trả "Organic" sớm (< ~8s) là placeholder, không phải kết luận.** Attribution thật của Adjust về ở p50 = 9s. Các row Adjust Organic ở p50 = 4s chính là chưa match xong.
3. **Referrer organic ≠ organic thật.** 49/75 user referrer-organic thực ra là paid theo Adjust (SAN claim, view-through). Chỉ nhóm này (~2,6% user) mới cần đợi Adjust.

---

## 2. Nguồn tín hiệu

### 2.1 Google Play Install Referrer
Package: `android_play_install_referrer` (hoặc platform channel gọi `InstallReferrerClient`).

Lấy đủ 3 field:
- `installReferrer` (string)
- `referrerClickTimestampSeconds` (int) — **0 = Play không ghi nhận click nào**
- `installBeginTimestampSeconds` (int)

Các dạng `installReferrer` thực tế đã gặp:

| Dạng | Phân loại |
|---|---|
| `gclid=…&gbraid=…&gad_source=…&gad_campaignid=…` | PAID (Google Ads) |
| `gclid=…&gbraid=…&gad_source=…` | PAID |
| `gclid=…` | PAID |
| `adjust_external_click_id=E.C.P.…` | PAID (TikTok qua Adjust) |
| `adjust_reftag=…` / `adjust_tracker=…` | PAID (Adjust tracker link) |
| `utm_source=apps.facebook.com&…&gclid=123456789` | PAID (gclid giả nhưng vẫn là click từ ad) |
| `utm_source=<bất kỳ>` khác `google-play` | PAID / campaign |
| `utm_source=google-play&utm_medium=organic` | ORGANIC candidate |
| `not set` / rỗng / null | ORGANIC candidate (cờ riêng `notSet`) |
| `network=d` | rác → coi ORGANIC candidate |

### 2.2 Adjust
SDK Flutter `adjust_sdk`.

- **Không** dựa vào `Adjust.getAttribution()` gọi 1 lần ở splash — hay trả null hoặc Organic mặc định.
- Dùng `AdjustConfig.attributionCallback` (attribution-changed listener). Callback chỉ fire khi Adjust thực sự có kết quả, và fire lại nếu attribution đổi (SAN claim muộn).
- `attribution.network`:
  - `Organic` → organic (chỉ tin khi đã qua ngưỡng thời gian, xem §3)
  - bất kỳ giá trị khác (`Google Ads ACI`, `TikTok SAN`, `Google Ads ACI::Campaign…`, …) → paid
  - null / rỗng → chưa có

---

## 3. Thuật toán

```
enum InstallSource { unknown, paid, organic }
enum Confidence  { none, provisional, final_ }
```

```
UNKNOWN (t=0, app start)
 │
 ├─ [A] Referrer về (~2s)
 │     ├─ isPaidReferrer(referrer)            → PAID  (final)     ← 97% user dừng ở đây
 │     └─ organic / not set / rác             → ORGANIC (provisional), mở cửa sổ chờ Adjust
 │
 ├─ [B] Adjust attributionCallback fire (bất kỳ lúc nào)
 │     ├─ network ≠ Organic                   → PAID  (final)     ← override mọi thứ
 │     └─ network == Organic
 │           ├─ elapsed ≥ ADJUST_TRUST_AFTER  → ORGANIC (final)
 │           └─ elapsed <  ADJUST_TRUST_AFTER → giữ provisional, tiếp tục chờ
 │
 └─ [C] Timeout ADJUST_WAIT_MAX mà chưa final
       └─ nếu đang provisional ORGANIC        → ORGANIC (provisional), lưu, re-check lần mở sau
```

Hằng số (lấy từ data, cần đo lại theo app):

```dart
const adjustTrustAfter = Duration(seconds: 8);   // Adjust Organic trước mốc này = chưa match
const adjustWaitMax    = Duration(seconds: 20);  // p95 attribution thật ≈ 23s ở both_full
const referrerTimeout  = Duration(seconds: 10);  // referrer p95 = 8s
```

Quy tắc ưu tiên:
1. PAID từ bất kỳ nguồn nào là **final**, không bao giờ hạ xuống organic.
2. ORGANIC chỉ **final** khi: referrer organic **và** Adjust Organic sau `adjustTrustAfter`.
3. Kết quả provisional phải được re-evaluate ở lần mở app tiếp theo nếu Adjust callback fire với network ≠ Organic (SAN có thể claim muộn vài giờ).

### 3.1 `isPaidReferrer`

```dart
bool isPaidReferrer(String? raw) {
  if (raw == null) return false;
  final r = Uri.decodeComponent(raw).trim().toLowerCase();
  if (r.isEmpty || r == 'not set') return false;

  final params = Uri.splitQueryString(r);
  const paidKeys = {
    'gclid', 'gbraid', 'wbraid', 'gad_source', 'gad_campaignid',
    'adjust_external_click_id', 'adjust_reftag', 'adjust_tracker',
    'adjust_campaign', 'fbclid', 'ttclid',
  };
  if (params.keys.any(paidKeys.contains)) return true;

  final src = params['utm_source'];
  final med = params['utm_medium'];
  if (src != null && src != 'google-play') return true;   // bất kỳ nguồn ngoài Play
  if (med != null && med != 'organic') return true;

  return false;
}
```

Bổ sung deterministic: nếu `referrerClickTimestampSeconds > 0` mà `isPaidReferrer` trả false → vẫn coi là paid candidate (Play ghi nhận click nhưng chuỗi bị cắt). Ngược lại `== 0` củng cố organic.

---

## 4. Persist & lifecycle

Lưu (SharedPreferences / Hive):

```json
{
  "source": "paid|organic|unknown",
  "confidence": "final|provisional",
  "decidedBy": "referrer|adjust|timeout",
  "referrerRaw": "...",
  "referrerClickTs": 0,
  "adjustNetwork": "...",
  "referrerAtMs": 2100,
  "adjustAtMs": 9300,
  "decidedAtMs": 2100,
  "installVersion": "1.0.9+109"
}
```

- Chỉ chạy classifier ở **first launch** (chưa có record). Các lần sau đọc cache.
- Adjust `attributionCallback` vẫn đăng ký ở mọi lần mở app: nếu record đang `organic` (bất kể final hay provisional) mà callback về network ≠ Organic → cập nhật thành `paid/final/adjust`.
- Không bao giờ ghi đè `paid` bằng `organic`.

---

## 5. Sử dụng

Expose 1 API:

```dart
/// Trả về ngay khi có quyết định final, hoặc provisional khi hết adjustWaitMax.
Future<InstallSourceResult> InstallSourceClassifier.resolve();

/// Stream để UI/logic khác nghe khi kết quả đổi (provisional → final, organic → paid).
Stream<InstallSourceResult> get changes;
```

Consumer (ad floor, onboarding flow, remote config bucket) nên:
- Dùng `resolve()` ở splash; 97% trả về trong ~2s.
- Với provisional organic: đối xử như organic nhưng subscribe `changes` để chuyển paid nếu Adjust claim muộn.

---

## 6. Logging để verify

Ghi 1 event (Firebase Analytics / backend) khi quyết định lần đầu, đủ field ở §4 cộng `bucket` như DB hiện tại. Sau 1 tuần kiểm tra:
- Tỉ lệ `organic/provisional` bị chuyển sang `paid` sau đó → nếu > ~5% thì tăng `adjustTrustAfter` / `adjustWaitMax`.
- Phân bố `adjustAtMs` khi network ≠ Organic → cập nhật lại `adjustTrustAfter` (mốc hiện tại 8s lấy từ p≈5 của attribution thật).

---

## 7. Caveat

- Data gốc lấy trong giai đoạn chạy ads mạnh (organic ~1%). Ngưỡng cần verify lại trên app có tỉ lệ organic cao.
- `not set` không đảm bảo user cài từ Play app (có thể web Play, restore, sideload). Log riêng để theo dõi.
- View-through / SAN claim có thể đến muộn hơn 20s (thậm chí giờ). Đó là lý do organic chỉ nên là kết luận "đến hiện tại", còn paid là vĩnh viễn.
- Trên iOS luồng khác hoàn toàn (không có Install Referrer, phụ thuộc SKAN/ATT + Adjust) — spec này chỉ cho Android.
