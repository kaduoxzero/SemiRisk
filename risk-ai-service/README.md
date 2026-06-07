# SemiRisk AI Analysis Service

Python service for AI risk analysis. It only analyzes event records sent by `ruoyi-system`.

## Run

```bash
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
set DEEPSEEK_API_KEY=your-key
set DEEPSEEK_MODEL=deepseekv4-pro
python -m uvicorn app:app --host 0.0.0.0 --port 18088
```
