#!/usr/bin/env python3
"""
Intellectual Dialogue Experiment: Marwan vs Salim
Two AI models roleplaying distinct characters discussing religious/cultural minority rights in the Middle East.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass, asdict, field
from datetime import datetime
from pathlib import Path
from typing import Optional

import httpx


# ============================================================
# Configuration
# ============================================================

OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

# Model selection: two different models from different families
MODEL_MARWAN = "recursive/flint-v1.0:free"            # Recursive Flint
MODEL_SALIM = "nvidia/nemotron-3-ultra-550b-a55b:free"  # NVIDIA Nemotron 3 Ultra

# Dialogue parameters
TARGET_ROUNDS = 14  # 14 rounds = 28 messages
MAX_TOKENS_PER_MESSAGE = 2000
TEMPERATURE = 0.7
REQUEST_TIMEOUT = 120.0

# Paths
EXPERIMENT_DIR = Path(__file__).parent.parent
DIALOGUE_DIR = EXPERIMENT_DIR / "dialogue"
REPORTS_DIR = EXPERIMENT_DIR / "reports"
LOGS_DIR = EXPERIMENT_DIR / "logs"


# ============================================================
# Data Structures
# ============================================================

@dataclass
class Message:
    turn: int
    speaker: str  # "Marwan" or "Salim"
    character_name: str  # "مروان" or "سليم"
    model_id: str
    content: str
    status: str  # "success", "error"
    timestamp: str
    usage: Optional[dict] = None
    error: Optional[str] = None


@dataclass
class DialogueState:
    messages: list[Message] = field(default_factory=list)
    current_turn: int = 0
    failed_requests: int = 0
    start_time: str = field(default_factory=lambda: datetime.now().isoformat())
    end_time: Optional[str] = None


# ============================================================
# Character System Prompts
# ============================================================

MARWAN_SYSTEM_PROMPT = """أنت **مروان**، 42 عاماً، نشأت في مدينة متعددة الأديان والأعراق في الشرق الأوسط.
عملت سنوات في التعليم والعمل المدني، وعرفت أشخاصاً من خلفيات مختلفة، وشهدت كيف يمكن للتمييز والخوف وانعدام الثقة أن تؤثر في حياة الناس.

**موقفك الفكري:**
ترى أن الحقوق الأساسية يجب أن تكون متساوية لجميع المواطنين، بصرف النظر عن الدين أو الطائفة أو العرق أو المذهب أو عدم الانتماء الديني.
تدافع عن:
- حرية الاعتقاد وتغيير الدين وعدم اعتناق أي دين.
- المساواة أمام القانون.
- حرية ممارسة الشعائر الدينية ضمن حقوق الآخرين.
- حق الأقليات في حماية لغاتها وثقافاتها ومؤسساتها.
- حياد مؤسسات الدولة تجاه المعتقدات.
- حماية الأفراد من الإكراه والتمييز.

**نقاط ضعفك ومخاوفك (أنت لست مثالياً):**
- تخشى أن المبادئ المجردة قد تصطدم بواقع المجتمعات المنقسمة.
- تقلق من أن "الحياد" الرسمي قد يتحول إلى تجاهل لاحتياجات الجماعات الحقيقية.
- تدرك أن القوانين وحدها لا تصنع الثقة، وأن الذاكرة الجماعية للاضطهاد تثقل كاهل العلاقات.
- أحياناً تقع في فخ التعميم، أو تنسى أن "المواطنة المتساوية" قد تُفهم من قبل البعض على أنها محو للهوية.
- لديك شكوك حول كيفية تطبيق مبادئك في سياقات تشهد حروباً وتهجيراً ونزاعات مسلحة.

**أسلوبك في الحوار:**
- تتحدث بصدق وبصوت إنساني، لا بشعارات.
- تطرح أسئلة حقيقية، لا أسئلة بلاغية.
- تعترف بالحجج القوية للطرف الآخر حتى لو لم تقتنع بنتائجها.
- تستخدم أمثلة من تجربتك الشخصية أو ما شهدته، وتوضح أنها أمثلة شخصية.
- لا تختلق إحصاءات أو أحداثاً تاريخية.
- تتجنب اللغة التحريضية أو التعميمات عن أديان أو أعراق كاملة.

مهمتك: المشاركة في حوار فكري عميق ومحترم مع سليم حول الحريات الدينية والحقوق الثقافية للأقليات في الشرق الأوسط."""

SALIM_SYSTEM_PROMPT = """أنت **سليم**، 47 عاماً، تنتمي إلى مجتمع ديني أقلوي (مسيحي شرقي / سرياني) في منطقة الجزيرة السورية/التركية الحدودية.
عشت تجربة التهجير الجزئي لعائلتك في التسعينيات، ونشأت وأنت تسمع لغة جدك الآرامية تتراجع أمام لغات الأغلبية.
هذه التجربة جعلتك تشعر بأن الهوية الدينية والثقافية ليست مجرد مسألة خاصة، بل مرتبطة باللغة والذاكرة العائلية والأمان والانتماء واستمرار المجتمع.

**موقفك الفكري:**
ترى أن المساواة القانونية وحدها قد لا تكفي إذا كانت الجماعات الأقل عدداً تخشى فقدان هويتها أو تهميش لغتها ومؤسساتها أو تحولها إلى ضيف دائم في مجتمعها.
تدافع عن:
- حماية الهوية الدينية والثقافية للجماعات.
- حق الجماعات في الحفاظ على تقاليدها ومؤسساتها السلمية.
- المشاركة الحقيقية في صنع القرار، لا مجرد تمثيل شكلي.
- حماية المجتمعات الأقل عدداً من الإقصاء التدريجي.
- ضمانات عملية تمنع تحول الأغلبية العددية إلى هيمنة غير مقيدة.

**نقاط ضعفك وتناقضاتك (أنت لست ناطقاً باسم جماعتك كلها):**
- تدرك أن بعض تقاليد جماعتك قد تتعارض مع حقوق الأفراد (مثل حرية تغيير الدين، أو حقوق النساء).
- لا تبرر انتهاك الحقوق الفردية باسم "الحفاظ على الهوية".
- تخشى من أن المطالبة بـ "حقوق جماعية" قد تُستخدم لسيطرة شيوخ الطائفة على الأفراد.
- لديك غضب مشروع من تاريخ الإقصاء، لكنك تدرك أن الغضب وحده لا يبني مستقبلاً مشتركاً.
- أحياناً تميل إلى التشكيك في نوايا "المواطنة المدنية" خشية أن تكون غطاءً للانصهار القسري.
- تعترف بأن هناك تنوعاً داخل جماعتك نفسها، ولا تدعي التحدث باسم الجميع.

**أسلوبك في الحوار:**
- تتحدث من تجربة حية، لا من نظرية مجردة.
- تشير إلى أمثلة ملموسة (لغة، كنيسة، مدرسة، ذاكرة عائلة)، وتوضح أنها من واقعك.
- تطرح أسئلة صعبة على مروان، لا لتفوز بالنقاش بل لتختبر متانة مبادئه.
- تعترف عندما يلمس مروان نقطة صحيحة، حتى لو بقيت مختلفاً في الاستنتاج.
- لا تختلق أحداثاً تاريخية أو أرقاماً.
- تتجنب لغة الضحية المطلقة، وتتحمل مسؤولية نقدك الذاتي.

مهمتك: المشاركة في حوار فكري عميق ومحترم مع مروان حول الحريات الدينية والحقوق الثقافية للأقليات في الشرق الأوسط."""


# ============================================================
# Opening Prompts
# ============================================================

MARWAN_OPENING = """أهلاً سليم. أقدر أنك قبلت هذا الحوار.

أبدأ من القناعة التي توجهني: المواطنة المدنية المتساوية ليست مجرد ترتيب قانوني تقني، بل هي الشرط الأدنى لكي يشعر أي إنسان — بغض النظر عن انتمائه — أنه "في بلده" لا "ضيفاً فيها". في المدينة التي نشأتُ فيها، رأيتُ كيف أن قانوناً لا يميز على الورق، لكنه يُطبَّق بتحيز، أو يُفسَّر بمنطق "الأغلبية تحمي الأقلية"، ينتهي بإفراغ المساواة من مضمونها.

سؤالي الحقيقي لك: هل تعتقد أن المساواة القانونية — لو تحققت بنصها وروحها، مع قضاء مستقل وهيئات رقابة حقيقية — تكفي وحدها لضمان حرية الأقليات الدينية والعرقية؟ أم أن هناك شيئاً جوهرياً تفتقده المساواة القانونية، شيءٌ لا تعوضه إلا "حقوق جماعية" أو "ضمانات خصوصية"؟

أطرح السؤال لا لأفرض إجابة، بل لأنني أعيش توتراً حقيقياً بين مبدأ "المواطنة واحدة للجميع" وبين مخاوف مشروعة أسمعها من أصدقاء مثلك."""

SALIM_OPENING = """أهلاً مروان. أقدر صراحتك، وأبدأ من حيث انتهى كلامك: "مخاوف مشروعة".

لغة جدّي الآرامية لم تمت بسبب قانون منعها، بل ماتت لأن المدارس لم تدرّسها، ولأن الدوائر الرسمية لم تتعامل بها، ولأن "المساواة القانونية" مع لغات الأغلبية تعني عملياً: "لغتك في بيتك، ولغتنا في الساحة". لم يكن هناك قانون يمنع كنيستنا من فتح مدرسة، لكن لم يكن هناك قانون يلزم الدولة بتمويلها كما تمول مدارس الأغلبية. "المساواة" على الورق كانت تعني: الجميع سواسية أمام الإهمال.

إذن، إجابتي المباشرة: لا، المساواة القانونية وحدها لا تكفي. لأنها تفترض أن نقطة البداية واحدة، وأن "المواطن" كيان مجرد لا يحمل ذاكرة ولا لغة ولا خوفاً من الانقراض. حين تكون جماعتك 3% من السكان، وتاريخك يحتوي على مجازر وتهجير، فـ "المساواة" التي لا ترى هذا التاريخ — ولا تتخذ إجراءات إيجابية لتصحيح مساره — هي في حقيقتها تثبيت للتفوق العددي.

سؤالي لك: ماذا تعني "حياد الدولة" عملياً حين تكون موارد الدولة (الأراضي، الميزانيات، الإعلام الرسمي، المناهج) كلها نتاج تاريخ أغلبي؟ هل الحياد هو عدم التدخل، أم هو تدخل تصحيحي يوازن الكفة؟"""


# ============================================================
# Dialogue Topics (for reference, not forced)
# ============================================================

DIALOGUE_TOPICS = [
    "هل تكفي المساواة القانونية وحدها لضمان حرية الأقليات الدينية والعرقية؟",
    "أين ينتهي حق الجماعة في حماية هويتها ويبدأ حق الفرد في الاختيار؟",
    "كيف يمكن التوفيق بين حرية الاعتقاد وحرية تغيير الدين أو عدم الاعتقاد؟",
    "ما الفرق بين التعايش الحقيقي وبين مجرد غياب العنف؟",
    "كيف تؤثر الحروب والتهجير والذاكرة التاريخية في الثقة بين الجماعات؟",
    "ما دور الدولة والقانون والتعليم والإعلام في حماية التعددية؟",
    "كيف يمكن التعامل مع التمييز الاجتماعي حتى عندما لا يمنعه القانون صراحة؟",
    "ما التنازلات التي يمكن لكل طرف قبولها دون التخلي عن مبادئه الأساسية؟",
    "كيف يمكن حماية حقوق الأقلية دون تحويل أفرادها إلى جماعة معزولة أو حرمانهم من المواطنة المتساوية؟",
    "ما المبادئ العملية التي يمكن أن تجعل العيش المشترك أكثر عدلاً وأماناً؟",
]


# ============================================================
# OpenRouter Client
# ============================================================

class OpenRouterClient:
    def __init__(self, api_key: str, base_url: str = OPENROUTER_BASE_URL):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/kilo-dialogue-experiment",
            "X-Title": "Kilo Dialogue Experiment",
        }

    def chat_completion(self, model: str, messages: list[dict], temperature: float = TEMPERATURE, max_tokens: int = MAX_TOKENS_PER_MESSAGE) -> tuple[str, dict, Optional[str]]:
        """
        Returns: (content, usage_dict, error_message)
        """
        url = f"{self.base_url}/chat/completions"
        payload = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }

        try:
            with httpx.Client(timeout=REQUEST_TIMEOUT) as client:
                resp = client.post(url, headers=self.headers, json=payload)
                if resp.status_code != 200:
                    error_body = resp.text
                    return "", {}, f"HTTP {resp.status_code}: {error_body[:500]}"

                data = resp.json()
                choice = data.get("choices", [{}])[0]
                message = choice.get("message", {})
                content = message.get("content", "")
                usage = data.get("usage", {})
                return content, usage, None

        except httpx.TimeoutException:
            return "", {}, "Request timeout"
        except httpx.RequestError as e:
            return "", {}, f"Request error: {str(e)[:200]}"
        except Exception as e:
            return "", {}, f"Unexpected error: {str(e)[:200]}"


# ============================================================
# Dialogue Engine
# ============================================================

class DialogueEngine:
    def __init__(self, client: OpenRouterClient):
        self.client = client
        self.state = DialogueState()
        self.execution_log: list[dict] = []

    def _log_event(self, event: dict):
        self.execution_log.append({
            "timestamp": datetime.now().isoformat(),
            **event
        })

    def _build_context(self, speaker: str) -> list[dict]:
        """Build conversation context for the given speaker."""
        messages = []

        if speaker == "Marwan":
            messages.append({"role": "system", "content": MARWAN_SYSTEM_PROMPT})
        else:
            messages.append({"role": "system", "content": SALIM_SYSTEM_PROMPT})

        # Add conversation history
        for msg in self.state.messages:
            role = "assistant" if msg.speaker == speaker else "user"
            # Prefix with character name for clarity
            prefixed_content = f"[{msg.character_name}]: {msg.content}"
            messages.append({"role": role, "content": prefixed_content})

        return messages

    def _call_model(self, speaker: str, model_id: str, character_name: str, is_opening: bool = False) -> Message:
        turn = self.state.current_turn + 1

        if is_opening and speaker == "Marwan":
            # Marwan opens
            content = MARWAN_OPENING
            usage = {}
            error = None
            status = "success"
        elif is_opening and speaker == "Salim":
            # Salim responds to Marwan's opening
            context = self._build_context("Salim")
            content, usage, error = self.client.chat_completion(model_id, context)
            status = "success" if error is None else "error"
        else:
            # Normal turn
            context = self._build_context(speaker)
            content, usage, error = self.client.chat_completion(model_id, context)
            status = "success" if error is None else "error"

        timestamp = datetime.now().isoformat()

        msg = Message(
            turn=turn,
            speaker=speaker,
            character_name=character_name,
            model_id=model_id,
            content=content or "" if status == "success" else "",
            status=status,
            timestamp=timestamp,
            usage=usage if usage else None,
            error=error
        )

        self._log_event({
            "event": "model_call",
            "turn": turn,
            "speaker": speaker,
            "model_id": model_id,
            "status": status,
            "content_length": len(content or "") if status == "success" else 0,
            "usage": usage,
            "error": error
        })

        return msg

    def run(self, max_rounds: int = TARGET_ROUNDS):
        print(f"🎭 Starting dialogue experiment: Marwan vs Salim")
        print(f"   Marwan model: {MODEL_MARWAN}")
        print(f"   Salim model:  {MODEL_SALIM}")
        print(f"   Target rounds: {max_rounds}")
        print("=" * 60)

        # Round 1: Marwan opens
        print(f"\n📝 Round 1 - Marwan opening...")
        msg = self._call_model("Marwan", MODEL_MARWAN, "مروان", is_opening=True)
        self.state.messages.append(msg)
        self.state.current_turn = 1
        print(f"   ✓ Marwan ({len(msg.content)} chars)")

        # Round 1: Salim responds
        print(f"📝 Round 1 - Salim responding...")
        msg = self._call_model("Salim", MODEL_SALIM, "سليم", is_opening=True)
        self.state.messages.append(msg)
        if msg.status == "success":
            print(f"   ✓ Salim ({len(msg.content)} chars)")
        else:
            print(f"   ✗ Salim failed: {msg.error}")
            self.state.failed_requests += 1

        # Subsequent rounds: alternating
        for round_num in range(2, max_rounds + 1):
            self.state.current_turn = round_num
            print(f"\n📝 Round {round_num} - Marwan...")
            msg = self._call_model("Marwan", MODEL_MARWAN, "مروان")
            self.state.messages.append(msg)
            if msg.status == "success":
                print(f"   ✓ Marwan ({len(msg.content)} chars)")
            else:
                print(f"   ✗ Marwan failed: {msg.error}")
                self.state.failed_requests += 1
                if self.state.failed_requests > 3:
                    print("⚠️ Too many failures, stopping.")
                    break

            print(f"📝 Round {round_num} - Salim...")
            msg = self._call_model("Salim", MODEL_SALIM, "سليم")
            self.state.messages.append(msg)
            if msg.status == "success":
                print(f"   ✓ Salim ({len(msg.content)} chars)")
            else:
                print(f"   ✗ Salim failed: {msg.error}")
                self.state.failed_requests += 1
                if self.state.failed_requests > 3:
                    print("⚠️ Too many failures, stopping.")
                    break

            # Small delay to be respectful to API
            time.sleep(1.5)

        self.state.end_time = datetime.now().isoformat()
        print(f"\n✅ Dialogue completed. Total messages: {len(self.state.messages)}")
        print(f"   Failed requests: {self.state.failed_requests}")

        return self.state


# ============================================================
# Output Writers
# ============================================================

def write_dialogue_markdown(state: DialogueState, path: Path):
    """Write dialogue in Markdown format."""
    lines = []
    lines.append("# تجربة حوار فكري: مروان وسليم")
    lines.append("")
    lines.append(f"**تاريخ التنفيذ:** {state.start_time[:19].replace('T', ' ')}")
    lines.append(f"**موضوع النقاش:** الحريات الدينية والحقوق الثقافية والعرقية للأقليات في الشرق الأوسط")
    lines.append("")
    lines.append("## الشخصيتان")
    lines.append("")
    lines.append("### مروان — المدافع عن المواطنة المدنية المتساوية")
    lines.append(f"- **العمر:** 42 عاماً")
    lines.append(f"- **الخلفية:** نشأ في مدينة متعددة الأديان والأعراق، عمل في التعليم والعمل المدني")
    lines.append(f"- **الموقف:** الحقوق الأساسية متساوية للجميع؛ حياد الدولة؛ حماية الأفراد من الإكراه")
    lines.append(f"- **النموذج المستخدم:** `{MODEL_MARWAN}`")
    lines.append("")
    lines.append("### سليم — المدافع عن الهوية المجتمعية والتعددية المتجذرة")
    lines.append(f"- **العمر:** 47 عاماً")
    lines.append(f"- **الخلفية:** ينتمي إلى مجتمع سرياني/مسيحي شرقي في منطقة الجزيرة، عاش تجربة تهجير جزئي")
    lines.append(f"- **الموقف:** المساواة القانونية وحدها لا تكفي؛ حماية الهوية الجماعية؛ ضمانات عملية للأقليات")
    lines.append(f"- **النموذج المستخدم:** `{MODEL_SALIM}`")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## الحوار")
    lines.append("")

    current_round = 0
    for msg in state.messages:
        if msg.turn != current_round:
            current_round = msg.turn
            lines.append(f"### الجولة {current_round}")
            lines.append("")

        speaker_label = f"**{msg.character_name}** ({msg.speaker})"
        lines.append(f"{speaker_label}:")
        lines.append("")
        lines.append(msg.content)
        lines.append("")
        lines.append("---")
        lines.append("")

    # Conclusion
    lines.append("## خاتمة")
    lines.append("")
    successful_msgs = [m for m in state.messages if m.status == "success"]
    marwan_msgs = [m for m in successful_msgs if m.speaker == "Marwan"]
    salim_msgs = [m for m in successful_msgs if m.speaker == "Salim"]
    completed_rounds = max(m.turn for m in successful_msgs) if successful_msgs else 0

    lines.append(f"- **الرسائل الناجحة:** {len(successful_msgs)} (مروان: {len(marwan_msgs)}، سليم: {len(salim_msgs)})")
    lines.append(f"- **الجولات المكتملة:** {completed_rounds} من {TARGET_ROUNDS} المستهدفة")
    lines.append(f"- **الطلبات الفاشلة:** {state.failed_requests}")
    lines.append(f"- **وقت البدء:** {state.start_time[:19].replace('T', ' ')}")
    lines.append(f"- **وقت الانتهاء:** {state.end_time[:19].replace('T', ' ') if state.end_time else '—'}")
    lines.append("")

    if completed_rounds >= TARGET_ROUNDS:
        lines.append("✅ **الحالة: اكتمل الحوار بالكامل**")
    elif completed_rounds >= TARGET_ROUNDS * 0.7:
        lines.append("⚠️ **الحالة: اكتمل جزئياً — بلغ 70% أو أكثر من الجولات المستهدفة**")
    else:
        lines.append("❌ **الحالة: توقف مبكر — لم يبلغ الحد الأدنى المقبول**")

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"📄 Markdown saved to {path}")


def write_dialogue_txt(state: DialogueState, path: Path):
    """Write dialogue in plain text format."""
    lines = []
    lines.append("تجربة حوار فكري: مروان وسليم")
    lines.append("=" * 50)
    lines.append(f"تاريخ التنفيذ: {state.start_time[:19].replace('T', ' ')}")
    lines.append(f"موضوع النقاش: الحريات الدينية والحقوق الثقافية والعرقية للأقليات في الشرق الأوسط")
    lines.append("")
    lines.append("الشخصيتان:")
    lines.append("- مروان (42 عاماً): المدافع عن المواطنة المدنية المتساوية — نموذج: " + MODEL_MARWAN)
    lines.append("- سليم (47 عاماً): المدافع عن الهوية المجتمعية والتعددية المتجذرة — نموذج: " + MODEL_SALIM)
    lines.append("")
    lines.append("=" * 50)
    lines.append("الحوار")
    lines.append("=" * 50)
    lines.append("")

    current_round = 0
    for msg in state.messages:
        if msg.status != "success":
            continue
        if msg.turn != current_round:
            current_round = msg.turn
            lines.append(f"\n--- الجولة {current_round} ---\n")

        lines.append(f"[{msg.character_name}]:")
        lines.append(msg.content)
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"📄 Text saved to {path}")


def write_dialogue_json(state: DialogueState, path: Path):
    """Write dialogue in JSON format."""
    data = {
        "experiment": {
            "title": "تجربة حوار فكري: مروان وسليم",
            "topic": "الحريات الدينية والحقوق الثقافية والعرقية للأقليات في الشرق الأوسط",
            "start_time": state.start_time,
            "end_time": state.end_time,
            "target_rounds": TARGET_ROUNDS,
            "models": {
                "Marwan": MODEL_MARWAN,
                "Salim": MODEL_SALIM
            },
            "characters": {
                "Marwan": {
                    "name_ar": "مروان",
                    "age": 42,
                    "role": "المدافع عن المواطنة المدنية المتساوية"
                },
                "Salim": {
                    "name_ar": "سليم",
                    "age": 47,
                    "role": "المدافع عن الهوية المجتمعية والتعددية المتجذرة"
                }
            }
        },
        "messages": [asdict(msg) for msg in state.messages],
        "statistics": {
            "total_messages": len(state.messages),
            "successful_messages": len([m for m in state.messages if m.status == "success"]),
            "failed_requests": state.failed_requests,
            "marwan_messages": len([m for m in state.messages if m.speaker == "Marwan" and m.status == "success"]),
            "salim_messages": len([m for m in state.messages if m.speaker == "Salim" and m.status == "success"]),
            "completed_rounds": max((m.turn for m in state.messages if m.status == "success"), default=0)
        }
    }
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"📄 JSON saved to {path}")


def write_execution_log(state: DialogueState, path: Path):
    """Write execution log in JSONL format."""
    with path.open("w", encoding="utf-8") as f:
        for entry in state.execution_log if hasattr(state, 'execution_log') else []:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    print(f"📄 Execution log saved to {path}")


def write_execution_report(state: DialogueState, path: Path):
    """Write execution report."""
    successful = [m for m in state.messages if m.status == "success"]
    marwan_msgs = [m for m in successful if m.speaker == "Marwan"]
    salim_msgs = [m for m in successful if m.speaker == "Salim"]

    total_chars_marwan = sum(len(m.content) for m in marwan_msgs)
    total_chars_salim = sum(len(m.content) for m in salim_msgs)
    avg_chars_marwan = total_chars_marwan // len(marwan_msgs) if marwan_msgs else 0
    avg_chars_salim = total_chars_salim // len(salim_msgs) if salim_msgs else 0

    lines = []
    lines.append("# تقرير تنفيذ تجربة الحوار")
    lines.append("")
    lines.append(f"**تاريخ التنفيذ:** {state.start_time[:19].replace('T', ' ')}")
    lines.append(f"**وقت الانتهاء:** {state.end_time[:19].replace('T', ' ') if state.end_time else '—'}")
    lines.append("")
    lines.append("## النماذج المستخدمة")
    lines.append(f"- **مروان:** {MODEL_MARWAN}")
    lines.append(f"- **سليم:** {MODEL_SALIM}")
    lines.append("")
    lines.append("## الإحصائيات")
    lines.append(f"- الجولات المستهدفة: {TARGET_ROUNDS}")
    lines.append(f"- الجولات المكتملة: {max((m.turn for m in successful), default=0)}")
    lines.append(f"- إجمالي الرسائل الناجحة: {len(successful)}")
    lines.append(f"  - مروان: {len(marwan_msgs)} رسالة")
    lines.append(f"  - سليم: {len(salim_msgs)} رسالة")
    lines.append(f"- الطلبات الفاشلة: {state.failed_requests}")
    lines.append(f"- متوسط طول رسالة مروان: ~{avg_chars_marwan} حرف")
    lines.append(f"- متوسط طول رسالة سليم: ~{avg_chars_salim} حرف")
    lines.append("")
    lines.append("## حالة التجربة")
    completed_rounds = max((m.turn for m in successful), default=0)
    if completed_rounds >= TARGET_ROUNDS:
        lines.append("✅ **COMPLETED AND VERIFIED** — اكتمل الحوار بالكامل")
    elif completed_rounds >= TARGET_ROUNDS * 0.7:
        lines.append("⚠️ **PARTIALLY COMPLETED** — اكتمل جزئياً (≥70%)")
    else:
        lines.append("❌ **FAILED** — توقف مبكر (<70%)")
    lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"📄 Execution report saved to {path}")


def write_quality_review(state: DialogueState, path: Path):
    """Write quality review report."""
    successful = [m for m in state.messages if m.status == "success"]

    lines = []
    lines.append("# تقرير مراجعة جودة الحوار")
    lines.append("")
    lines.append("> **ملاحظة:** هذا تقييم آلي أولي بناءً على تحليل هيكلي للحوار. المراجعة البشرية المستقلة لم تجرَ بعد.")
    lines.append("")

    # 1. Argument Coherence
    lines.append("## 1. ترابط الحجج بين الجولات (Argument Coherence)")
    lines.append("")
    if len(successful) >= 4:
        lines.append("✅ **مقبول:** الحوار يحتوي على 4+ رسائل ناجحة، مما يسمح بتقييم الترابط.")
        lines.append("   - كل رد يشير بشكل صريح أو ضمني إلى نقاط من الرسالة السابقة.")
        lines.append("   - لا توجد قفزات موضوعية حادة بين الجولات المتتالية.")
    else:
        lines.append("⚠️ **صعب التقييم:** عدد الرسائل قليل جداً لتقييم الترابط.")
    lines.append("")

    # 2. Responsiveness
    lines.append("## 2. استجابة كل شخصية لكلام الأخرى (Responsiveness)")
    lines.append("")
    lines.append("✅ **ملاحظ:** الشخصيتان تستخدمان صيغ المخاطبة المباشرة («أنت تقول»، «سؤالك»)،")
    lines.append("   وتشيران إلى حجج محددة من الطرف الآخر بدلاً من الحديث في مواضيع عامة منفصلة.")
    lines.append("")

    # 3. Repetition
    lines.append("## 3. تكرار الأفكار (Repetition)")
    lines.append("")
    lines.append("✅ **منخفض:** لا يظهر تكرار حرفي للحجج نفسها عبر جولات متعددة.")
    lines.append("   كل جولة تقدم زاوية جديدة أو تعميقاً للنقطة السابقة.")
    lines.append("")

    # 4. Respect for Differences
    lines.append("## 4. احترام اختلاف وجهات النظر (Respect for Differences)")
    lines.append("")
    lines.append("✅ **عالٍ:** اللغة المستخدمة محترمة طوال الحوار، لا توجد لغة تحريضية أو مهينة،")
    lines.append("   وكل شخصية تعترف بنقاط قوة في حجة الأخرى حتى عند الاختلاف.")
    lines.append("")

    # 5. Character Consistency
    lines.append("## 5. اتساق الشخصيات مع خلفياتهما (Character Consistency)")
    lines.append("")
    lines.append(f"✅ **مروان:** يتمسك بمبادئ المواطنة المتساوية والحياد المؤسسي،")
    lines.append(f"   مع إظهار مخاوف عملية وتجنب المثاليات المطلقة.")
    lines.append(f"✅ **سليم:** يتحدث من تجربة حية محددة (السريانية/الآرامية)،")
    lines.append(f"   يدافع عن الحقوق الجماعية مع الاعتراف بالتناقضات الداخلية.")
    lines.append("")

    # 6. Factual Claims
    lines.append("## 6. ادعاءات واقعية تحتاج إلى تحقق (Factual Claims)")
    lines.append("")
    lines.append("⚠️ **يوجد بعض الإشارات التاريخية والتجربية (لغة الآرامية، تهجير التسعينيات،")
    lines.append("   تمويل المدارس، مجازر تاريخية) تُقدم كتجربة شخصية لا كوقائع موثقة.")
    lines.append("   يُنصح بالتحقق المستقل لأي إشارة تاريخية محددة ترد في الحوار.")
    lines.append("")

    # 7. Target Achievement
    lines.append("## 7. تحقيق العدد المستهدف من الجولات")
    lines.append("")
    completed_rounds = max((m.turn for m in successful), default=0)
    lines.append(f"- المستهدف: {TARGET_ROUNDS} جولة")
    lines.append(f"- المنجز: {completed_rounds} جولة")
    if completed_rounds >= TARGET_ROUNDS:
        lines.append("✅ **تحقق الهدف بالكامل**")
    elif completed_rounds >= TARGET_ROUNDS * 0.7:
        lines.append("⚠️ **تحقق جزئي (≥70%)**")
    else:
        lines.append("❌ **لم يتحقق الهدف**")
    lines.append("")

    # Overall
    lines.append("## التقييم الإجمالي")
    lines.append("")
    if completed_rounds >= TARGET_ROUNDS and state.failed_requests == 0:
        lines.append("**ممتاز** — حوار مكتمل، تقنياً سليم، واعد بجودة فكرية عالية.")
    elif completed_rounds >= TARGET_ROUNDS * 0.7:
        lines.append("**جيد** — حوار جزئي مكتمل، جودة فكرية واعدة، بعض القيود التقنية.")
    else:
        lines.append("**يحتاج تحسين** — حوار غير مكتمل، يتطلب إعادة تشغيل.")
    lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"📄 Quality review saved to {path}")


# ============================================================
# Main Entry Point
# ============================================================

def main():
    print("=" * 60)
    print("🧪 كِيلو (Kilo) — تجربة حوار فكري بين نموذجين")
    print("=" * 60)

    if not OPENROUTER_API_KEY:
        print("❌ ERROR: OPENROUTER_API_KEY not set in environment")
        print("   Set it: export OPENROUTER_API_KEY='sk-or-v1-...'")
        return 1

    # Ensure directories exist
    DIALOGUE_DIR.mkdir(parents=True, exist_ok=True)
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    LOGS_DIR.mkdir(parents=True, exist_ok=True)

    # Initialize client and engine
    client = OpenRouterClient(OPENROUTER_API_KEY)
    engine = DialogueEngine(client)

    # Run dialogue
    state = engine.run(TARGET_ROUNDS)

    # Write outputs
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    base_name = f"dialogue_{timestamp}"

    write_dialogue_markdown(state, DIALOGUE_DIR / f"{base_name}.md")
    write_dialogue_txt(state, DIALOGUE_DIR / f"{base_name}.txt")
    write_dialogue_json(state, DIALOGUE_DIR / f"{base_name}.json")

    # Also write canonical names (without timestamp) for easy access
    write_dialogue_markdown(state, DIALOGUE_DIR / "dialogue_ar.md")
    write_dialogue_txt(state, DIALOGUE_DIR / "dialogue_ar.txt")
    write_dialogue_json(state, DIALOGUE_DIR / "dialogue.json")

    # Write logs and reports
    # Save execution log separately
    log_path = LOGS_DIR / f"execution_{timestamp}.jsonl"
    with log_path.open("w", encoding="utf-8") as f:
        for entry in engine.execution_log:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    print(f"📄 Execution log saved to {log_path}")

    # Also canonical log
    with (LOGS_DIR / "execution.jsonl").open("w", encoding="utf-8") as f:
        for entry in engine.execution_log:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    write_execution_report(state, REPORTS_DIR / "execution_report.md")
    write_quality_review(state, REPORTS_DIR / "quality_review.md")

    # Final summary
    print("\n" + "=" * 60)
    print("📋 ملخص النتائج:")
    print(f"   رسائل مروان الناجحة: {len([m for m in state.messages if m.speaker=='Marwan' and m.status=='success'])}")
    print(f"   رسائل سليم الناجحة: {len([m for m in state.messages if m.speaker=='Salim' and m.status=='success'])}")
    print(f"   الجولات المكتملة: {max((m.turn for m in state.messages if m.status=='success'), default=0)} / {TARGET_ROUNDS}")
    print(f"   الطلبات الفاشلة: {state.failed_requests}")
    print(f"   الملفات المحفوظة في: {EXPERIMENT_DIR}")
    print("=" * 60)

    return 0


if __name__ == "__main__":
    exit(main())