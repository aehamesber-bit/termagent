# دليل التشغيل السريع لـ TermAgent (العربية)

دليل مبسط باللغة العربية الفصحى لتشغيل **TermAgent** على **Termux (Android)**.

---

## 1. المتطلبات الأساسية

- **Termux** مثبت على جهاز Android (يُنصح بتثبيته من F-Droid أو GitHub Releases، وليس من متجر Play).
- **Python 3.9+** و **pip** و **git**.
- اتصال بالإنترنت (لتحميل النماذج من OpenCode Zen).

---

## 2. تثبيت المتطلبات

افتح Termux وشغّل الأوامر التالية:

```sh
pkg update && pkg install -y python python-pip git
pip install httpx rich           # مطلوبان
pip install prompt_toolkit       # اختياري: لتحسين تجربة الإدخال (سجل الأسطر، تحرير متعدد الأسطر)
```

---

## 3. تنزيل المستودع وتشغيله

```sh
git clone https://github.com/aehamesber-bit/termagent.git
cd termagent
pip install -e .                 # يثبّت أمر `termagent` عالمياً
```

> **ملاحظة:** الأمر `pip install -e .` يجعل الأمر `termagent` متاحاً من أي مجلد.

---

## 4. تشغيل الوكيل (مثال بسيط)

```sh
termagent
```

سيبدأ الوكيل فوراً باستخدام النموذج الافتراضي المجاني `north-mini-code-free` من **OpenCode Zen** — لا يحتاج مفتاح API ولا تسجيل دخول.

**مثال جلسة:**

```
> اكتب دالة بايثون تحسب المضروب (factorial)
> /models
> 2
> switched to deepseek-v4-flash-free free
> اشرح لي كود الدالة التي كتبتها للتو
> /exit
```

---

## 5. أهم أوامر REPL

| الأمر | الوصف |
|-------|-------|
| `/help` | عرض قائمة الأوامر المتاحة |
| `/models` | عرض النماذج المتاحة والتبديل بينها |
| `/model <id>` | التبديل لنموذج محدد مباشرة |
| `/approve` | تفعيل/إلغاء الموافقة التلقائية على الأدوات |
| `/save` | حفظ الجلسة الحالية |
| `/load` | تحميل جلسة محفوظة |
| `/sessions` | عرض الجلسات المحفوظة |
| `/clear` | مسح سجل المحادثة الحالي |
| `/config` | فتح محرر الإعدادات التفاعلي |
| `/cwd` | عرض/تغيير مجلد العمل |
| `/tools` | عرض الأدوات المتاحة |
| `/sys` | عرض برومبت النظام الحالي |
| `/paste` | الدخول في وضع اللصق المتعدد الأسطر (أنهِ بسطر يحتوي على `.` فقط) |
| `/exit` | الخروج من REPL |

---

## 6. وضع الأمر الواحد (One-shot)

لتنفيذ مهمة واحدة والخروج:

```sh
termagent "أعد سرد ملفات البايثون في هذا المجلد"
```

---

## 7. خيارات سطر الأوامر الشائعة

```sh
termagent                  # REPL تفاعلي في المجلد الحالي
termagent "مهمتك هنا"      # وضع الأمر الواحد
termagent -c /path/to/project   # تعيين مجلد عمل مختلف
termagent -a               # تفعيل الموافقة التلقائية على جميع الأدوات (خطر — راجع التنبيه أدناه)
termagent -s <session-id>  # استئناف جلسة محفوظة
termagent -b <base_url> -k <key> -m <model>  # استخدام مزود آخر
```

---

## 8. ⚠️ تنبيه مهم: خيار `-a` (الموافقة التلقائية)

> **`termagent -a` يفعّل الموافقة التلقائية على تنفيذ جميع أدوات الأوامر (bash، write، edit، إلخ).**
>
> هذا يعني أن الوكيل سينفذ أوامر الصدفة ويعدّل الملفات **دون طلب إذن منك**. استخدم هذا الخيار **بحذر شديد** وفقط في بيئات اختبارية أو مع مهام تثق بها تماماً. في الاستخدام العادي، اترك الموافقة التلقائية معطلة (الوضع الافتراضي) ताकि تتم مراجعة كل عملية قبل تنفيذها.

---

## 9. الإعداد لمزودين آخرين (اختياري)

الافتراضي: **OpenCode Zen** (مجاني، بدون مفتاح).

لاستخدام مزود آخر، اختر **إحدى** الطريقتين:

**أ) متغيرات بيئة:**
```sh
export TERMAGENT_API_KEY="sk-..."
export TERMAGENT_BASE_URL="https://api.openai.com/v1"
export TERMAGENT_MODEL="gpt-4o-mini"
```

**ب) إعداد تفاعلي (يُحفظ في `~/.config/termagent/config.json`):**
```sh
termagent --config
```

**أمثلة لمزودين معروفين:**
```sh
# OpenCode Zen (مدفوع)
termagent -b https://opencode.ai/zen/v1 -k $ZEN_KEY -m glm-5.2

# OpenRouter
termagent -b https://openrouter.ai/api/v1 -k $OPENROUTER_KEY -m "anthropic/claude-3.5-sonnet"

# Ollama محلي
termagent -b http://localhost:11434/v1 -k dummy -m llama3.1

# LM Studio / llama.cpp
termagent -b http://localhost:1234/v1 -k dummy -m my-model
```

---

## 10. الأدوات المتاحة للوكيل

- `bash` — تنفيذ أوامر صدفة
- `read` — قراءة ملف
- `write` — كتابة ملف جديد
- `edit` — تعديل ملف موجود
- `ls` — عرض محتويات مجلد
- `glob` — البحث عن ملفات بنمط (glob pattern)
- `grep` — البحث عن نص داخل ملفات
- `todo` — إدارة قائمة مهام (إضافة، تحديث، حذف، عرض)

---

## 11. هيكل المشروع (للمطورين)

```
termagent/
  cli.py        # نقطة الدخول، أوامر REPL، الحلقة الرئيسية
  agent.py      # حلقة استدعاء الأدوات
  client.py     # عميل Streaming متوافق مع OpenAI API
  config.py     # الإعدادات (متغيرات بيئة + ملف JSON)
  zen.py        # كتالوج نماذج OpenCode Zen
  session.py    # حفظ/استرجاع الجلسات (JSONL)
  tui.py        # واجهة الطرفية (rich) + مطالبات الموافقة
  tools/        # أدوات الوكيل
    base.py     # سجل الأدوات + السياق
    bash.py read.py write.py edit.py ls.py glob.py grep.py todo.py
```

---

## 12. الترخيص

**MIT License** — راجع ملف `LICENSE` في المستودع.

---

> **تم إنشاء هذا الدليل بناءً على `README.md` الرسمي للمشروع.**  
> للمزيد من التفاصيل والتحديثات، راجع المستودع الأصلي: https://github.com/aehamesber-bit/termagent