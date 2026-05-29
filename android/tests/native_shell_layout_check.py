from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "app" / "src" / "main" / "java" / "com" / "codex" / "mobilecontrol" / "MainActivity.kt"
LAYOUT = ROOT / "app" / "src" / "main" / "res" / "layout" / "activity_main.xml"
STRINGS = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
PREFERENCES = ROOT / "app" / "src" / "main" / "java" / "com" / "codex" / "mobilecontrol" / "GatewayPreferences.kt"


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise AssertionError(message)


def main() -> None:
    main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
    layout = LAYOUT.read_text(encoding="utf-8")
    strings = STRINGS.read_text(encoding="utf-8")
    preferences = PREFERENCES.read_text(encoding="utf-8")

    require(
        main_activity,
        "GatewayDefaults.configFor(gatewayUrl, token)",
        "MainActivity 必须使用用户输入的网关地址和 Token 组装配置"
    )
    require(
        preferences,
        "GatewayDefaults.configFor(url, token)",
        "GatewayPreferences 必须同时恢复持久化的网关地址和 Token"
    )

    require(layout, "gatewayUrlInput", "登录页必须展示 Gateway URL 输入框")
    require(layout, 'android:inputType="textPassword|textNoSuggestions"', "Token 输入框必须隐藏明文")
    require(strings, "gateway_url_label", "strings.xml 必须提供网关地址文案")


if __name__ == "__main__":
    main()
