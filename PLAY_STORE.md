# Pofuduk — Google Play listing (draft, Turkish)

| Field | Value |
|---|---|
| App name (30) | Pofuduk: Konuşan Hava Durumu |
| Package | `com.pofuduk.app` (cannot change later) |
| Category | Hava Durumu (Weather) |
| Price | Ücretsiz — reklam yok, uygulama içi satın alma yok |
| Countries | Yalnızca Türkiye |
| Default language | Türkçe (tr-TR) |
| Contact e-mail | *(user's support address — entered in Play Console)* |
| Privacy policy URL | `https://onurercan88.github.io/pofuduk-android/gizlilik.html` (after GitHub Pages is enabled, see RELEASE_CHECKLIST.md) |
| Graphics | `docs/store/icon-512.png`, `docs/store/feature-1024x500.png`, `docs/store/screenshots/*.png` (1080×1920) |

## Short description (max 80)
Havayı Türkçe anlatan sevimli bulut: yağmur saati, ne giysem, seyahat havası.

## Full description (max 4000)
Pofuduk, hava durumunu sana Türkçe anlatan sevimli bir bulut arkadaş.

• Günün havasını doğal Türkçe konuşmayla ve birebir altyazıyla anlatır; söylediği kelime altyazıda vurgulanır
• Yağmurun ne zaman başlayacağını, ne kadar süreceğini ve ne kadar kuvvetli olacağını söyler
• "Ne giysem?" ile hava durumuna göre kıyafet önerir ve nedenini açıklar
• "Seyahat Hava Durumu" ile gideceğin yerin havasını bulunduğun yerle karşılaştırır ve yanına ne alacağını söyler
• "Yağmur Başlamadan Haber Ver" bildirimleriyle şemsiyeni unutmazsın (isteğe bağlı)
• "Pofuduk Günaydın" ile güne hava özetiyle başlarsın
• Sorularını yazarak ya da sesli sorabilirsin: "Yarın hava nasıl?", "Akşam yağmur var mı?"
• Güneşte gözlük takar, yağmurda şemsiyesini açar, karda atkısını sarar — dokununca da güler
• Sesi kapalıyken her şey yazılı görünür; TalkBack, büyük yazı ve karanlık tema desteklenir
• Hesap yok, reklam yok; tercihlerin yalnızca cihazında saklanır

Pofuduk yalnızca gerçek tahmin verilerine dayanır ve resmi meteorolojik uyarıları kendi tahmininden asla üretmez; resmi uyarılar için MGM'nin sayfasına yönlendirir.

Hava verisi: Open-Meteo.com (CC BY 4.0).

## Release notes (tr-TR) — 1.0.0
Pofuduk Android'de! Konuşan hava durumu, yağmur bildirimleri, kıyafet önerileri ve seyahat havası.

## Screenshots (phone, 1080×1920, demo data with the "Örnek veri" label, fixed clock 14 Kasım)
1. `1-home-sunny` — Güneşli sabah, konuşurken altyazı
2. `2-rainy-afternoon` — Yağmurda şemsiyeli Pofuduk
3. `3-clothing` — Karlı gün, "Ne giysem?"
4. `4-question` — "Yağmur saat kaçta başlayacak?" cevabı
5. `5-travel` — Bursa seyahat havası, yanına al listesi
6. `6-settings` — Kişiselleştirme ve ses ayarları
7. `7-stormy-dark` — Fırtınada karanlık tema

Regenerate: see RELEASE_CHECKLIST.md › "Store graphics".

## Data safety form (proposal — the user confirms when filling it in)
Reasoning: the app has **no server, no account, no analytics, no ads SDK**. Everything below is sent by the app itself
only to answer the user's request in real time.

| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | **Yes** (conservative: approximate location leaves the device to fetch the forecast) |
| Is all collected data encrypted in transit? | **Yes** (HTTPS only) |
| Can users request that data be deleted? | **Yes** — in-app "Verilerimi sil" (no server-side data exists) |
| Location › Approximate location | Collected: **yes**; Shared: **no** (Open-Meteo acts as the service that answers the request); Processed ephemerally: **yes**; Required: **no** (a city can be picked instead); Purpose: **App functionality** |
| Location › Precise location | **Not collected** (only `ACCESS_COARSE_LOCATION`; fixes rounded to ~1 km) |
| Audio / Voice | **Not collected by the app**: speech recognition is done by the device's recognition service (on-device when available); no audio leaves through the app |
| Personal info, financial, health, messages, photos, contacts, app activity, web history, device IDs | **Not collected** |
| Crash logs / diagnostics | **Not collected** (no crash SDK) |

## Other Play Console declarations
- **Ads:** No ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating (IARC):** no violence, no user interaction/chat with other users, no location sharing with others,
  no purchases → expected "3+ / Herkes".
- **Target audience:** recommend **13+** (avoids the Families policy track; the app is still fine for children). If you
  choose younger ages too, the app already meets the main Families rules (no ads, no data collection), but review takes longer.
- **News app:** No. **Health app:** No. **Government app:** No. **Financial features:** None.
- **Permissions:** `ACCESS_COARSE_LOCATION`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `INTERNET` — each asked only when the
  related feature is used; no background location, no exact alarms.
