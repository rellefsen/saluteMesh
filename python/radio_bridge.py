#!/usr/bin/env python3
"""JSON-lines Meshtastic radio bridge for the SALUTE Mesh command-center UI.

Same serial + Bluetooth path as Block Status (charcTool): official Meshtastic
Python library. The app never sets channel PSK. One JSON object per line on stdin;
one JSON object per line on stdout.
"""

from __future__ import annotations

import json
import logging
import subprocess
import sys
import threading
import time
from typing import Any

logging.basicConfig(level=logging.INFO, format="radio_bridge: %(message)s", stream=sys.stderr)
logger = logging.getLogger("salute.radio")

_active_lock = threading.RLock()
_interface = None
_channel_index: int | None = None
_channel_name = "salute"
_connected_label = ""
_pubsub_ready = False


def emit(payload: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _ensure_pubsub() -> None:
    global _pubsub_ready
    if _pubsub_ready:
        return
    from pubsub import pub  # type: ignore[import-untyped]

    pub.subscribe(_on_packet, "meshtastic.receive.text")
    _pubsub_ready = True


def _on_packet(packet: dict, interface: object | None = None) -> None:
    del interface
    with _active_lock:
        wanted = _channel_index
    if wanted is not None:
        rx = packet.get("channel", 0)
        try:
            if int(rx) != int(wanted):
                return
        except (TypeError, ValueError):
            if rx != wanted:
                return
    text = _extract_text(packet)
    if not text:
        return
    from_id = packet.get("fromId")
    emit({"event": "incoming", "text": text, "fromId": "" if from_id is None else str(from_id)})


def _extract_text(packet: dict) -> str | None:
    try:
        decoded = packet.get("decoded") or {}
        text = decoded.get("text")
        if text:
            return str(text).strip()
        payload = decoded.get("payload")
        if isinstance(payload, (bytes, bytearray)):
            return payload.decode("utf-8", errors="replace").strip() or None
        if isinstance(payload, str) and payload.strip():
            return payload.strip()
        if packet.get("text"):
            return str(packet["text"]).strip()
    except Exception:
        logger.exception("parse incoming")
    return None


def list_serial_ports() -> list[str]:
    try:
        import meshtastic.util

        return list(meshtastic.util.findPorts(True) or [])
    except Exception:
        logger.exception("serial scan")
        return []


def list_ble_devices() -> list[dict[str, str]]:
    found: dict[str, str] = {}
    try:
        from meshtastic.ble_interface import BLEInterface  # type: ignore[import-untyped]

        for device in BLEInterface.scan():
            address = str(getattr(device, "address", "") or "").strip()
            name = str(getattr(device, "name", "") or "").strip() or address
            if address:
                found[address.upper()] = name
    except Exception:
        logger.exception("BLE scan")
    for address, name in _bluez_devices("Paired"):
        found.setdefault(address.upper(), name)
    return [{"address": addr, "name": name} for addr, name in found.items()]


def _bluez_devices(kind: str) -> list[tuple[str, str]]:
    try:
        result = subprocess.run(
            ["bluetoothctl", "devices", kind],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return []
    if result.returncode != 0:
        return []
    devices: list[tuple[str, str]] = []
    for line in result.stdout.splitlines():
        parts = line.strip().split(maxsplit=2)
        if len(parts) < 2 or parts[0] != "Device":
            continue
        address = parts[1].strip()
        name = parts[2].strip() if len(parts) > 2 else address
        if address:
            devices.append((address, name))
    return devices


def _bluez_disconnect(address: str) -> None:
    address = address.strip()
    if not address:
        return
    try:
        subprocess.run(
            ["bluetoothctl", "disconnect", address],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass


def _close_interface() -> None:
    global _interface, _channel_index, _connected_label
    iface = _interface
    _interface = None
    _channel_index = None
    _connected_label = ""
    if iface is not None and hasattr(iface, "close"):
        try:
            iface.close()
        except Exception:
            pass


def _channel_role_disabled(channel: object) -> bool:
    role = getattr(channel, "role", None)
    text = str(role)
    return "DISABLED" in text.upper()


def list_channels(iface: object) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    candidates: list[object] = []
    for owner in (iface, getattr(iface, "localNode", None)):
        if owner is None:
            continue
        try:
            node = owner.getNode("^local") if hasattr(owner, "getNode") else owner
        except Exception:
            node = owner
        channels = getattr(node, "channels", None)
        if channels:
            candidates.extend(list(channels))
    seen: set[int] = set()
    for channel in candidates:
        try:
            if _channel_role_disabled(channel):
                continue
            index = int(getattr(channel, "index", 0))
            settings = getattr(channel, "settings", None)
            name = str(getattr(settings, "name", "") or "") if settings is not None else ""
            if index in seen:
                continue
            seen.add(index)
            out.append({"index": index, "name": name})
        except Exception:
            continue
    out.sort(key=lambda item: int(item["index"]))
    return out


def _resolve_channel(iface: object, name: str) -> int | None:
    try:
        node = iface.getNode("^local")
        channel = node.getChannelByName(name)
        if channel is None:
            return None
        return int(channel.index)
    except Exception:
        logger.exception("resolve channel %s", name)
        return None


def connect(kind: str, port: str, address: str, channel_name: str) -> dict[str, Any]:
    global _interface, _channel_index, _channel_name, _connected_label
    _close_interface()
    _ensure_pubsub()
    _channel_name = (channel_name or "salute").strip() or "salute"
    if kind == "bluetooth":
        import meshtastic.ble_interface  # type: ignore[import-untyped]

        mac = address.strip()
        if mac:
            _bluez_disconnect(mac)
            time.sleep(2.0)
        iface = meshtastic.ble_interface.BLEInterface(address=mac or None)
        label = f"bluetooth:{mac or 'auto'}"
    else:
        import meshtastic.serial_interface  # type: ignore[import-untyped]

        chosen = port.strip() or None
        if not chosen:
            ports = list_serial_ports()
            if len(ports) == 1:
                chosen = ports[0]
            elif not ports:
                raise RuntimeError("No Meshtastic USB serial radio found.")
            else:
                raise RuntimeError("Several USB radios found. Pick one serial port.")
        iface = meshtastic.serial_interface.SerialInterface(
            devPath=chosen,
            connectNow=True,
            timeout=15,
        )
        if getattr(iface, "stream", None) is None:
            raise RuntimeError(f"Could not open serial port {chosen}")
        label = str(chosen)
    channels = list_channels(iface)
    index = _resolve_channel(iface, _channel_name)
    if index is None and channels:
        salute = next((c for c in channels if str(c.get("name", "")).lower() == _channel_name.lower()), None)
        if salute:
            index = int(salute["index"])
    with _active_lock:
        _interface = iface
        _channel_index = index
        _connected_label = label
    if index is None:
        raise RuntimeError(
            f"Radio opened on {label}, but channel '{_channel_name}' was not found. "
            "Set the channel name on the radios first."
        )
    return {
        "connected": True,
        "port": label,
        "channelName": _channel_name,
        "channelIndex": index,
        "channels": channels,
    }


def send_text(text: str) -> None:
    with _active_lock:
        iface = _interface
        index = _channel_index
    if iface is None or index is None:
        raise RuntimeError("Radio is not connected.")
    iface.sendText(text, wantAck=False, channelIndex=index)


def handle(msg: dict[str, Any]) -> dict[str, Any]:
    cmd = str(msg.get("cmd") or "")
    if cmd == "ping":
        return {"ok": True, "bridge": "salute-radio"}
    if cmd == "list_serial":
        return {"ok": True, "ports": list_serial_ports()}
    if cmd == "list_ble":
        return {"ok": True, "devices": list_ble_devices()}
    if cmd == "connect":
        result = connect(
            kind=str(msg.get("type") or "serial"),
            port=str(msg.get("port") or ""),
            address=str(msg.get("address") or ""),
            channel_name=str(msg.get("channel") or "salute"),
        )
        result["ok"] = True
        return result
    if cmd == "send":
        send_text(str(msg.get("text") or ""))
        return {"ok": True}
    if cmd == "disconnect":
        _close_interface()
        return {"ok": True, "connected": False}
    raise RuntimeError(f"Unknown command {cmd}")


def main() -> None:
    emit({"event": "ready", "message": "Radio bridge started."})
    try:
        for raw in sys.stdin:
            line = raw.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError as exc:
                emit({"ok": False, "error": f"Bad JSON: {exc}"})
                continue
            req_id = msg.get("id")
            try:
                reply = handle(msg)
            except Exception as exc:
                logger.exception("command failed")
                reply = {"ok": False, "error": str(exc).strip() or exc.__class__.__name__}
            if req_id is not None:
                reply["id"] = req_id
            emit(reply)
    finally:
        _close_interface()


if __name__ == "__main__":
    main()
