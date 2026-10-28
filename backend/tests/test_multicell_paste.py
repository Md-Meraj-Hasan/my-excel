from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HTML_FILES = [
    ROOT / 'backend' / 'niceexcel_api' / 'static' / 'index.html',
    ROOT / 'app' / 'src' / 'main' / 'assets' / 'niceexcel_index.html',
]


def test_web_and_native_support_tabular_clipboard_paste():
    for path in HTML_FILES:
        html = path.read_text(encoding='utf-8')
        assert 'async function pasteTabularText(text)' in html
        assert "row.split('\\t')" in html
        assert "addEventListener('paste'" in html
        assert "json('PATCH',{sheet:activeSheet,cells})" in html
        assert 'Pasted data exceeds the visible grid' in html
