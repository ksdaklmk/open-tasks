#!/usr/bin/env python3
"""Seed a synthetic Open Tasks workspace for the Play listing screenshots.

Drives a disposable AVD over adb with `uiautomator dump` plus `input tap`, so
it needs no test hooks in the app and works against a real release build.

    python3 scripts/seed-listing-workspace.py        # projects, then tasks 1-5
    python3 scripts/seed-listing-workspace.py 4      # resume at task 4

Resuming matters because the AVD is booted read-only: a failed run must not
recreate what already exists. Every step verifies the screen it expects before
acting and raises with the current screen text when it does not find it, so a
failure names its own cause instead of running the rest of the chain against
the wrong surface.

It leaves three things to do by hand, because they are one-offs rather than
repeated shapes: a sixth task assigned to a project and moved to In progress,
the project due date, and the milestone. See RELEASING.md for the viewport,
status-bar and capture recipe that goes with this.

Traps this encodes, each of which cost real time to find:
  * bottom navigation sits 126 px above the bottom edge, so its coordinates
    must come from the live screen height, not a hard-coded viewport;
  * the IME covers a sheet's confirm button and the second field of a
    two-field sheet, so the keyboard is hidden between fields;
  * the runtime-permission dialog's title also contains "Allow", so the
    button is matched by widget class rather than by text;
  * Material date-picker day labels follow the device locale, so the label is
    read back from the picker instead of being formatted here.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/Users/kk/Library/Android/sdk/platform-tools/adb"
NAV = {"home": 100, "tasks": 320, "projects": 540, "schedule": 760, "more": 980}


def _nav_y():
    """Bottom navigation sits a fixed 126 px above the bottom edge, so read the
    live screen height instead of hard-coding a viewport."""
    out = subprocess.run(
        [ADB, "shell", "wm", "size"], capture_output=True, text=True
    ).stdout
    sizes = re.findall(r"(\d+)x(\d+)", out)
    height = int(sizes[-1][1])  # "Override size" wins when present
    return height - 126


NAV_Y = _nav_y()


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True)


def dump():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = adb("shell", "cat", "/sdcard/ui.xml").stdout
        if "<hierarchy" in x:
            return x
        time.sleep(1)
    raise RuntimeError("uiautomator dump failed")


def nodes(x=None):
    out = []
    for n in ET.fromstring(x or dump()).iter("node"):
        m = re.findall(r"\d+", n.get("bounds", ""))
        if len(m) == 4:
            b = tuple(map(int, m))
            out.append((n.get("text", ""), n.get("content-desc", ""), b))
    return out


def texts():
    return [f"{t}|{d}" for t, d, _ in nodes() if t or d]


def find(q, exact=False):
    ql = q.lower()
    hits = []
    for t, d, b in nodes():
        if (t == q or d == q) if exact else (ql in t.lower() or ql in d.lower()):
            hits.append((t, d, b))
    return hits


def wait(q, secs=30, exact=False):
    end = time.time() + secs
    while time.time() < end:
        h = find(q, exact)
        if h:
            return h
        time.sleep(1)
    raise RuntimeError(f"timeout waiting for {q!r}; screen: {texts()[:14]}")


def tap_xy(x, y, pause=1.0):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(pause)


def tap(q, idx=0, exact=False, pause=1.2):
    h = find(q, exact)
    if not h:
        raise RuntimeError(f"no node {q!r}; screen: {texts()[:14]}")
    b = h[idx][2]
    tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, pause)


def type_text(s):
    adb("shell", "input", "text", s.replace(" ", "%s").replace("'", "\\'"))
    time.sleep(0.8)


def back():
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1)


def hide_keyboard():
    """The IME can cover a sheet's confirm button at this viewport."""
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method").stdout:
        back()


def scroll_to(q, tries=10, exact=False):
    for _ in range(tries):
        if find(q, exact):
            return True
        adb("shell", "input", "swipe", "540", "1500", "540", "900", "300")
        time.sleep(1)
    return False


def scroll_up_to(q, tries=14, exact=False):
    for _ in range(tries):
        if find(q, exact):
            return True
        adb("shell", "input", "swipe", "540", "700", "540", "1600", "300")
        time.sleep(0.8)
    return False


def to_top():
    for _ in range(6):
        adb("shell", "input", "swipe", "540", "700", "540", "1600", "300")
    time.sleep(1)


def nav(tab):
    tap_xy(NAV[tab], NAV_Y, 2.0)


def new_project(name, summary):
    nav("projects")
    wait("Create a new project")
    tap("Create a new project")
    wait("New project")
    tap("Project name")
    type_text(name)
    tap("Summary (optional)")
    type_text(summary)
    hide_keyboard()
    tap("Create project", pause=2.0)
    wait("Project created")
    tap("Back to projects", pause=1.5)
    print(f"  project: {name}")


def quick_add(title):
    nav("tasks")
    wait("Quick add task")
    tap("Quick add task")
    wait("Quick add")
    tap("Task title")
    type_text(title)
    hide_keyboard()
    tap("Add task", pause=2.0)
    wait(title)
    print(f"  task: {title}")


def open_task(title):
    nav("tasks")
    wait(title)
    hits = [h for h in find(title) if h[0] == title]
    b = hits[0][2]
    tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 2.0)
    wait("Task details")


def set_project(project):
    if not scroll_to("Dependencies"):
        raise RuntimeError("Organisation section not reached")
    tap("Inbox", exact=True)
    wait(project)
    tap(project, exact=True, pause=1.5)


def set_priority(level):
    if not scroll_to("Urgent"):
        raise RuntimeError("Priority row not reached")
    tap(level, exact=True)


def add_tag(tag):
    if not scroll_to("Create or add tag"):
        raise RuntimeError("Tags row not reached")
    tap("Create or add tag")
    type_text(tag)
    hide_keyboard()
    tap("Create and add tag", pause=1.5)


def set_due(day_offset):
    """Pick a due date by reading the picker's own day labels."""
    if not scroll_to("new dates use 17:00"):
        raise RuntimeError("Due row not reached")
    hits = find("Choose date")
    if not hits:
        raise RuntimeError(f"no due Choose date; screen: {texts()[:14]}")
    b = hits[-1][2]  # Due is below Start
    tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 2.0)
    wait("Select date")
    import datetime
    target = datetime.date.today() + datetime.timedelta(days=day_offset)
    day_nodes = [
        (t, d, bb) for t, d, bb in nodes()
        if re.search(rf"\b{target.day}\b", t or "")
        and target.strftime("%B") in (t or "")
        and str(target.year) in (t or "")
    ]
    if not day_nodes:
        raise RuntimeError(f"day {target} not in picker; labels: {[t for t,_,_ in nodes() if 'September' in t][:5]}")
    bb = day_nodes[0][2]
    tap_xy((bb[0] + bb[2]) // 2, (bb[1] + bb[3]) // 2, 1.2)
    tap("Use date", pause=2.0)


def add_checklist(items):
    if not scroll_to("New checklist item"):
        raise RuntimeError("Checklist not reached")
    for it in items:
        tap("New checklist item")
        type_text(it)
        hide_keyboard()
        tap("Add checklist item", pause=1.5)


def tap_button(label):
    """Tap by widget class. The runtime-permission dialog's title also contains
    the word 'Allow', so matching text alone hits the title and the dialog
    stays up."""
    for n in ET.fromstring(dump()).iter("node"):
        if n.get("class") == "android.widget.Button" and \
                (n.get("text") or "").strip().lower() == label.lower():
            b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
            tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, 2.0)
            return True
    return False


def set_reminder(option):
    if not scroll_to(option):
        raise RuntimeError(f"reminder option {option} not reached")
    tap(option, exact=True, pause=2.0)
    if find("Allow Open Tasks") and tap_button("Allow"):
        print("  notifications allowed")
    if find("Allow Open Tasks"):
        raise RuntimeError("permission dialog still showing")


def set_estimate(option):
    if not scroll_to("Estimate"):
        raise RuntimeError("Estimate not reached")
    tap(option, exact=True)


def leave_task():
    if not scroll_up_to("Back to tasks"):
        raise RuntimeError(f"editor top not reached; screen: {texts()[:10]}")
    tap("Back to tasks", pause=1.5)


def main():
    # `python3 seed.py N` resumes at task N, leaving finished work untouched;
    # the AVD is read-only so a failed run must not redo what already exists.
    start = int(sys.argv[1]) if len(sys.argv) > 1 else 0

    if start == 0:
        print("projects:")
        for name, summary in [
            ("Plan the week", "Priorities and time blocks for the coming week"),
            ("Quarterly report", "Numbers, narrative and sign-off for Q3"),
            ("Design review", "Onboarding flow mockups and feedback"),
            ("Venue booking", "Launch event space, catering and AV"),
        ]:
            new_project(name, summary)
        nav("projects")
        print("  ->", [t for t in texts() if "projects •" in t])

    print("tasks:")
    if start <= 1:
        t1 = "Review the onboarding mockups"
        quick_add(t1)
        open_task(t1)
        set_project("Design review")
        set_priority("High")
        add_tag("design")
        set_due(0)
        add_checklist(["Check the empty states", "Confirm the colour contrast"])
        print("  ->", [t for t in texts() if "complete" in t][:1])
        leave_task()

    if start <= 2:
        t2 = "Draft the quarterly report outline"
        quick_add(t2)
        open_task(t2)
        set_project("Quarterly report")
        set_priority("High")
        add_tag("writing")
        set_due(1)
        set_estimate("2 hr")
        leave_task()

    if start <= 3:
        t3 = "Book the venue for the launch event"
        quick_add(t3)
        open_task(t3)
        set_project("Venue booking")
        set_priority("Medium")
        add_tag("events")
        set_due(2)
        set_reminder("1 day before")
        leave_task()

    if start <= 4:
        t4 = "Send the report to finance"
        quick_add(t4)
        open_task(t4)
        set_project("Quarterly report")
        set_priority("Medium")
        set_due(4)
        leave_task()

    if start <= 5:
        t5 = "Plan next week's priorities"
        quick_add(t5)
        open_task(t5)
        set_project("Plan the week")
        set_priority("Low")
        add_tag("planning")
        set_due(5)
        leave_task()

    nav("tasks")
    print("done:", [t for t in texts() if "open •" in t])


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("SEED FAILED:", e, file=sys.stderr)
        sys.exit(1)
