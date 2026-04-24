#!/usr/bin/env python3
"""Generate branded hardware reference charts for README."""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from pathlib import Path
import numpy as np

# ── Paths ──────────────────────────────────────────────────────────────────
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "documentation" / "images" / "charts"
OUT.mkdir(parents=True, exist_ok=True)

FONT_DISPLAY = str(ROOT / "src" / "main" / "resources" / "fonts" / "Orbitron-Bold.ttf")
FONT_BODY = str(ROOT / "src" / "main" / "resources" / "fonts" / "JetBrainsMono-Regular.ttf")

# ── Brand Colors (from MxTTheme.kt) ──────────────────────────────────
BACKGROUND = "#18130A"
SURFACE_HIGH = "#2F291F"
ON_SURFACE = "#EDE1D1"
PRIMARY_CTR = "#F9B925"
SECONDARY = "#E5C282"
OUTLINE = "#9D8F79"
GRID_COLOR = "#40504533"  # fallback — computed below with alpha

# ── Register Fonts ─────────────────────────────────────────────────────────
font_manager.fontManager.addfont(FONT_DISPLAY)
font_manager.fontManager.addfont(FONT_BODY)
TITLE_FONT = font_manager.FontProperties(fname=FONT_DISPLAY).get_name()
BODY_FONT = font_manager.FontProperties(fname=FONT_BODY).get_name()


def style_chart(ax, title, ylabel, xlabel=None):
    """Apply brand styling to an axes object."""
    ax.set_facecolor(BACKGROUND)
    ax.figure.set_facecolor(BACKGROUND)

    ax.set_title(title, fontfamily=TITLE_FONT, fontsize=16, color=ON_SURFACE, pad=14)
    ax.set_ylabel(ylabel, fontfamily=BODY_FONT, fontsize=11, color=ON_SURFACE)
    if xlabel:
        ax.set_xlabel(xlabel, fontfamily=BODY_FONT, fontsize=11, color=ON_SURFACE)

    ax.tick_params(colors=ON_SURFACE, labelsize=10)
    for label in ax.get_xticklabels() + ax.get_yticklabels():
        label.set_fontfamily(BODY_FONT)

    ax.yaxis.grid(True, color=OUTLINE, alpha=0.25, linewidth=0.5)
    ax.xaxis.grid(False)

    for spine in ax.spines.values():
        spine.set_visible(False)


def add_bar_labels(ax, bars, fmt="{:.0f}", color=ON_SURFACE):
    """Add value labels centered above each bar."""
    for bar in bars:
        h = bar.get_height()
        ax.text(
            bar.get_x() + bar.get_width() / 2, h + (ax.get_ylim()[1] * 0.015),
            fmt.format(h),
            ha="center", va="bottom",
            fontfamily=BODY_FONT, fontsize=11, fontweight="bold", color=color,
        )


# ═══════════════════════════════════════════════════════════════════════════
# Dataset 1: Turbo Airflow (per turbo)
# ═══════════════════════════════════════════════════════════════════════════
turbos = ["K03", "K04", "RS6", "650R", "770R"]
airflow_gs = [120, 166, 196, 287, 370]

fig, ax = plt.subplots(figsize=(10, 5), dpi=150)
bars = ax.bar(turbos, airflow_gs, color=PRIMARY_CTR, width=0.55, zorder=3)
style_chart(ax, "Turbo Airflow", "Airflow (g/s per turbo)")
add_bar_labels(ax, bars)
ax.set_ylim(0, max(airflow_gs) * 1.15)
fig.tight_layout()
fig.savefig(OUT / "turbo_airflow.png", facecolor=BACKGROUND)
plt.close(fig)
print("✓ turbo_airflow.png")


# ═══════════════════════════════════════════════════════════════════════════
# Dataset 2: MAF Airflow (by housing)
# ═══════════════════════════════════════════════════════════════════════════
housings = ["73mm\n(Stock)", "83mm\n(RS4)", "85mm\n(Hitachi)", "89mm\n(HPX)"]
maf_gs = [337, 498, 493, 800]

fig, ax = plt.subplots(figsize=(10, 5), dpi=150)
bars = ax.bar(housings, maf_gs, color=PRIMARY_CTR, width=0.55, zorder=3)
style_chart(ax, "MAF Housing Airflow", "Max Airflow (g/s)")
add_bar_labels(ax, bars)
ax.set_ylim(0, max(maf_gs) * 1.15)
fig.tight_layout()
fig.savefig(OUT / "maf_airflow.png", facecolor=BACKGROUND)
plt.close(fig)
print("✓ maf_airflow.png")


# ═══════════════════════════════════════════════════════════════════════════
# Dataset 3: Fuel Demand (2.7T biturbo total, 10:1 AFR)
# ═══════════════════════════════════════════════════════════════════════════
fuel_cc = [1000, 1400, 1600, 2200, 3024]

fig, ax = plt.subplots(figsize=(10, 5), dpi=150)
bars = ax.bar(turbos, fuel_cc, color=PRIMARY_CTR, width=0.55, zorder=3)
style_chart(ax, "Total Fuel Demand (10:1 AFR)", "Fuel Demand (cc/min)")
add_bar_labels(ax, bars)
ax.set_ylim(0, max(fuel_cc) * 1.15)
fig.tight_layout()
fig.savefig(OUT / "fuel_demand.png", facecolor=BACKGROUND)
plt.close(fig)
print("✓ fuel_demand.png")


# ═══════════════════════════════════════════════════════════════════════════
# Dataset 4: Injector Size (V6 biturbo, 6 cylinders)
# ═══════════════════════════════════════════════════════════════════════════
injector_cc = [340, 470, 540, 740, 1000]

fig, ax = plt.subplots(figsize=(10, 5), dpi=150)
bars = ax.bar(turbos, injector_cc, color=PRIMARY_CTR, width=0.55, zorder=3)
style_chart(ax, "Injector Size (V6 Biturbo, 6 Cyl)", "Injector Size (cc/min)")
add_bar_labels(ax, bars)
ax.set_ylim(0, max(injector_cc) * 1.15)
fig.tight_layout()
fig.savefig(OUT / "injector_size.png", facecolor=BACKGROUND)
plt.close(fig)
print("✓ injector_size.png")


# ═══════════════════════════════════════════════════════════════════════════
# Dataset 5: Load + HP (dual Y axis)
# ═══════════════════════════════════════════════════════════════════════════
load_pct = [155, 210, 240, 354, 460]
hp = [320, 440, 500, 740, 960]
STOCK_LIMIT = 191

fig, ax1 = plt.subplots(figsize=(10, 5), dpi=150)
ax2 = ax1.twinx()

x = np.arange(len(turbos))
bar_w = 0.40

bars1 = ax1.bar(x - bar_w / 2, load_pct, bar_w, color=PRIMARY_CTR, label="Load %", zorder=3)
bars2 = ax2.bar(x + bar_w / 2, hp, bar_w, color=SECONDARY, label="HP", zorder=3)

# Stock M-box limit reference line
ax1.axhline(y=STOCK_LIMIT, color=OUTLINE, linestyle="--", linewidth=1.2, zorder=2)
ax1.text(
    -0.4, STOCK_LIMIT + 8,
    f"Stock M-box limit ({STOCK_LIMIT}%)",
    fontfamily=BODY_FONT, fontsize=9, color=OUTLINE,
    ha="left", va="bottom",
)

style_chart(ax1, "Theoretical Load & Horsepower (2.7L V6)", "Load (%)")
ax2.set_ylabel("Horsepower", fontfamily=BODY_FONT, fontsize=11, color=SECONDARY)
ax2.tick_params(colors=SECONDARY, labelsize=10)
for label in ax2.get_yticklabels():
    label.set_fontfamily(BODY_FONT)
ax2.spines["right"].set_visible(False)

ax1.set_xticks(x)
ax1.set_xticklabels(turbos)
ax1.set_ylim(0, max(load_pct) * 1.18)
ax2.set_ylim(0, max(hp) * 1.18)

add_bar_labels(ax1, bars1, fmt="{:.0f}%", color=ON_SURFACE)
add_bar_labels(ax2, bars2, fmt="{:.0f}", color=SECONDARY)

# Combined legend
lines1, labels1 = ax1.get_legend_handles_labels()
lines2, labels2 = ax2.get_legend_handles_labels()
ax1.legend(
    lines1 + lines2, labels1 + labels2,
    loc="upper left", fontsize=10,
    facecolor=SURFACE_HIGH, edgecolor=OUTLINE, labelcolor=ON_SURFACE,
    prop={"family": BODY_FONT},
)

fig.tight_layout()
fig.savefig(OUT / "load_hp.png", facecolor=BACKGROUND)
plt.close(fig)
print("✓ load_hp.png")

print(f"\nAll charts saved to {OUT}")
