use std::path::Path;

use anyhow::Result;
use chrono::{DateTime, Local, TimeZone};
use tokio::fs;
use tracing::error;

use backend_application::AppState;
use backend_domain::{AnomalyRow, ReportSummary, RuntimeConfig};

pub async fn schedule_reports(state: AppState) {
    loop {
        let next = next_report_time(&state.config);
        let duration = next.signed_duration_since(Local::now());
        let sleep_ms = duration.num_milliseconds().max(0) as u64;
        tokio::time::sleep(std::time::Duration::from_millis(sleep_ms)).await;

        if let Err(err) = generate_daily_report(&state).await {
            error!("report generation failed: {}", err);
        }
    }
}

pub async fn generate_daily_report(state: &AppState) -> Result<()> {
    let date = Local::now().format("%Y-%m-%d").to_string();
    let summary = state.anomaly_repo.fetch_summary(&date).await?;
    let detail = state.anomaly_repo.fetch_anomalies(&date, None).await?;

    let report_dir = Path::new(&state.config.report_dir);
    fs::create_dir_all(report_dir).await?;
    let path = report_dir.join(format!("{}.html", date));

    let html = render_report(&date, &summary, &detail);
    fs::write(&path, html).await?;

    if let Some(url) = &state.config.webhook_url {
        let report_link = format!("{}/reports/{}", state.config.public_base_url, date);
        send_webhook(url, state.config.webhook_template.as_deref(), &date, &summary, &report_link).await?;
    }

    Ok(())
}

pub fn render_report(date: &str, summary: &ReportSummary, detail: &[AnomalyRow]) -> String {
    let mut rows = String::new();
    for item in detail.iter().take(500) {
        let risk_class = match item.risk_level.as_str() {
            "HIGH" => "risk-high",
            "MEDIUM" => "risk-medium",
            "LOW" => "risk-low",
            _ => "risk-unknown",
        };
        rows.push_str(&format!(
            "<tr data-risk=\"{risk}\" data-player=\"{player}\" data-item=\"{item}\">\
            <td class=\"time\">{time}</td>\
            <td class=\"player\">{player}</td>\
            <td class=\"item\">{item}</td>\
            <td class=\"count\">{count}</td>\
            <td class=\"risk\"><span class=\"badge {risk_class}\">{risk}</span></td>\
            <td class=\"reason\">{reason}</td>\
            </tr>",
            time = item.event_time,
            player = item.player_name,
            item = item.item_id,
            count = item.count,
            risk = item.risk_level,
            risk_class = risk_class,
            reason = item.reason
        ));
    }

    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Lattice Report {date}</title>
<style>
:root {{
  --bg: #0b1220;
  --surface: #0f172a;
  --panel: #111827;
  --card: #ffffff;
  --ink: #0f172a;
  --muted: #64748b;
  --border: #e2e8f0;
  --shadow: rgba(15, 23, 42, 0.14);
  --accent: #2563eb;
  --high: #dc2626;
  --medium: #f59e0b;
  --low: #16a34a;
  --unknown: #64748b;
}}
* {{ box-sizing: border-box; }}
body {{
  margin: 0;
  font-family: "IBM Plex Sans", "Source Sans 3", "Noto Sans SC", sans-serif;
  background: radial-gradient(circle at top, #1e293b 0%, #0f172a 55%, #0b1220 100%);
  color: #e2e8f0;
}}
.page {{ max-width: 1200px; margin: 0 auto; padding: 32px 20px 48px; }}
.hero {{
  background: linear-gradient(135deg, rgba(37,99,235,0.18), rgba(15,23,42,0.95));
  border-radius: 20px;
  padding: 28px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.35);
}}
.hero h1 {{
  margin: 0 0 6px;
  font-size: 28px;
  font-family: "Sora", "IBM Plex Sans", "Source Sans 3", sans-serif;
  letter-spacing: 0.01em;
}}
.hero p {{ margin: 0; color: var(--muted); font-size: 14px; }}
.summary {{
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-top: 18px;
}}
.card {{
  background: rgba(255,255,255,0.96);
  color: var(--ink);
  padding: 16px 18px;
  border-radius: 14px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
}}
.card .label {{
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--muted);
}}
.card .value {{
  font-size: 22px;
  font-weight: 700;
  margin-top: 6px;
}}
.controls {{
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin: 22px 0 12px;
}}
.search {{
  flex: 1 1 260px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f8fafc;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px 12px;
  color: var(--ink);
}}
.search span {{
  font-size: 12px;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.1em;
}}
.search input {{
  border: none;
  outline: none;
  width: 100%;
  font-size: 14px;
  background: transparent;
  font-family: "IBM Plex Sans", "Source Sans 3", "Noto Sans SC", sans-serif;
}}
.segmented {{
  display: inline-flex;
  background: #f1f5f9;
  border-radius: 12px;
  padding: 4px;
  border: 1px solid var(--border);
}}
.segmented button {{
  border: none;
  background: transparent;
  color: #475569;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-family: "IBM Plex Sans", "Source Sans 3", "Noto Sans SC", sans-serif;
}}
.segmented button.active {{
  background: #ffffff;
  color: #1e293b;
  box-shadow: 0 4px 10px rgba(15, 23, 42, 0.1);
}}
.controls .count {{ margin-left: auto; color: var(--muted); font-size: 13px; }}
.table-wrap {{
  background: #ffffff;
  color: var(--ink);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 12px 28px var(--shadow);
}}
.table {{ width: 100%; border-collapse: collapse; font-size: 14px; }}
.table thead th {{
  text-align: left;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #64748b;
  background: #f1f5f9;
  padding: 12px 14px;
  position: sticky;
  top: 0;
  z-index: 1;
}}
.table tbody td {{
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}}
.table tbody tr:nth-child(even) {{ background: #f8fafc; }}
.table tbody tr:hover {{ background: #eef2ff; }}
.table .count {{
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-family: "IBM Plex Mono", "JetBrains Mono", "SFMono-Regular", monospace;
}}
.table .item {{
  font-family: "IBM Plex Mono", "JetBrains Mono", "SFMono-Regular", monospace;
  font-size: 12px;
  color: #1f2937;
}}
.badge {{
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: white;
}}
.risk-high {{ background: var(--high); }}
.risk-medium {{ background: var(--medium); }}
.risk-low {{ background: var(--low); }}
.risk-unknown {{ background: var(--unknown); }}
.empty {{
  padding: 20px;
  text-align: center;
  color: var(--muted);
}}
.footer {{
  margin-top: 16px;
  color: var(--muted);
  font-size: 12px;
}}
@media (max-width: 720px) {{
  .controls {{ flex-direction: column; align-items: stretch; }}
  .controls .count {{ margin-left: 0; }}
  .table thead th:nth-child(1),
  .table tbody td:nth-child(1) {{
    display: none;
  }}
}}
</style>
</head>
<body>
<div class="page">
  <section class="hero">
    <h1 data-i18n="title">Item Anomaly Daily Report</h1>
    <p data-i18n="subtitle" data-date="{date}" data-limit="500">Date: {date} · Showing the latest 500 events</p>
    <div class="summary">
      <div class="card"><div class="label" data-i18n="summary_high">High Risk</div><div class="value">{high}</div></div>
      <div class="card"><div class="label" data-i18n="summary_medium">Medium Risk</div><div class="value">{medium}</div></div>
      <div class="card"><div class="label" data-i18n="summary_low">Low Risk</div><div class="value">{low}</div></div>
      <div class="card"><div class="label" data-i18n="summary_total">Total</div><div class="value">{total}</div></div>
    </div>
  </section>

  <section class="controls">
    <div class="search">
      <span data-i18n="search_label">Search</span>
      <input id="search" type="search" placeholder="Player, item, reason" data-i18n-placeholder="search_placeholder" />
    </div>
    <div class="segmented" id="risk">
      <button type="button" data-risk-filter="ALL" class="active" data-i18n="filter_all">All</button>
      <button type="button" data-risk-filter="HIGH" data-i18n="filter_high">High</button>
      <button type="button" data-risk-filter="MEDIUM" data-i18n="filter_medium">Medium</button>
      <button type="button" data-risk-filter="LOW" data-i18n="filter_low">Low</button>
    </div>
    <div class="count" id="visible-count"></div>
  </section>

  <div class="table-wrap">
    <table class="table">
      <thead><tr>
        <th data-i18n="th_time">Time</th>
        <th data-i18n="th_player">Player</th>
        <th data-i18n="th_item">Item</th>
        <th data-i18n="th_count">Count</th>
        <th data-i18n="th_risk">Risk</th>
        <th data-i18n="th_reason">Reason</th>
      </tr></thead>
      <tbody id="rows">
      {rows}
      </tbody>
    </table>
    <div class="empty" id="empty" style="display:none;" data-i18n="empty">No rows match the current filters.</div>
  </div>

  <div class="footer" data-i18n="footer">Low risk rows usually indicate a matched transfer chain for audit reference.</div>
</div>
<script>
  const search = document.getElementById('search');
  const risk = document.getElementById('risk');
  const rows = Array.from(document.querySelectorAll('#rows tr'));
  const count = document.getElementById('visible-count');
  const empty = document.getElementById('empty');
  let currentRisk = 'ALL';
  let currentDict = {{}};
  const fallbackDict = {{
    title: 'Item Anomaly Daily Report',
    subtitle: 'Date: {{date}} · Showing the latest {{limit}} events',
    summary_high: 'High Risk',
    summary_medium: 'Medium Risk',
    summary_low: 'Low Risk',
    summary_total: 'Total',
    search_label: 'Search',
    search_placeholder: 'Player, item, reason',
    filter_all: 'All',
    filter_high: 'High',
    filter_medium: 'Medium',
    filter_low: 'Low',
    th_time: 'Time',
    th_player: 'Player',
    th_item: 'Item',
    th_count: 'Count',
    th_risk: 'Risk',
    th_reason: 'Reason',
    empty: 'No rows match the current filters.',
    footer: 'Low risk rows usually indicate a matched transfer chain for audit reference.',
    showing: 'Showing {{visible}} / {{total}}'
  }};

  function formatTemplate(template, data) {{
    return template.replace(/\{{(.*?)\}}/g, (_, key) => {{
      const value = data[key.trim()];
      return value !== undefined ? value : '';
    }});
  }}

  function applyI18n(dict) {{
    currentDict = dict;
    document.documentElement.lang = dict.lang || 'en';
    const elements = document.querySelectorAll('[data-i18n]');
    elements.forEach(el => {{
      const key = el.getAttribute('data-i18n');
      if (!key || !dict[key]) return;
      const data = {{}};
      Array.from(el.attributes).forEach(attr => {{
        if (attr.name.startsWith('data-') && attr.name !== 'data-i18n') {{
          data[attr.name.replace('data-', '').replace(/-/g, '')] = attr.value;
        }}
      }});
      el.textContent = formatTemplate(dict[key], data);
    }});
    const placeholders = document.querySelectorAll('[data-i18n-placeholder]');
    placeholders.forEach(el => {{
      const key = el.getAttribute('data-i18n-placeholder');
      if (key && dict[key]) {{
        el.setAttribute('placeholder', dict[key]);
      }}
    }});
    document.title = dict.title || document.title;
  }}

  function loadI18n() {{
    const params = new URLSearchParams(window.location.search);
    const lang = params.get('lang') || 'en';
    if (lang === 'en') {{
      applyI18n(fallbackDict);
      return;
    }}
    fetch(`/i18n/${{lang}}.json`).then(resp => {{
      if (!resp.ok) throw new Error('missing');
      return resp.json();
    }}).then(data => {{
      applyI18n(Object.assign({{}}, fallbackDict, data, {{ lang }}));
    }}).catch(() => {{
      applyI18n(fallbackDict);
    }});
  }}

  Array.from(risk.querySelectorAll('button')).forEach(btn => {{
    btn.addEventListener('click', () => {{
      currentRisk = btn.dataset.riskFilter;
      risk.querySelectorAll('button').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      applyFilter();
    }});
  }});
  function applyFilter() {{
    const keyword = search.value.trim().toLowerCase();
    let visible = 0;
    rows.forEach(row => {{
      const text = row.textContent.toLowerCase();
      const matchRisk = currentRisk === 'ALL' || row.dataset.risk === currentRisk;
      const matchText = !keyword || text.includes(keyword);
      const show = matchRisk && matchText;
      row.style.display = show ? '' : 'none';
      if (show) visible += 1;
    }});
    const template = currentDict.showing || fallbackDict.showing;
    count.textContent = formatTemplate(template, {{ visible: visible, total: rows.length }});
    empty.style.display = visible === 0 ? 'block' : 'none';
  }}
  search.addEventListener('input', applyFilter);
  loadI18n();
  applyFilter();
</script>
</body>
</html>"#,
        date = date,
        high = summary.high,
        medium = summary.medium,
        low = summary.low,
        total = summary.high + summary.medium + summary.low,
        rows = rows,
    )
}

async fn send_webhook(
    url: &str,
    template: Option<&str>,
    date: &str,
    summary: &ReportSummary,
    link: &str,
) -> Result<()> {
    let template = template.unwrap_or(
        r#"{"message":"{date} 异常: 高{high} 中{medium} 低{low} {link}"}"#,
    );
    let payload = template
        .replace("{date}", date)
        .replace("{high}", &summary.high.to_string())
        .replace("{medium}", &summary.medium.to_string())
        .replace("{low}", &summary.low.to_string())
        .replace("{link}", link);

    let client = reqwest::Client::new();
    client
        .post(url)
        .header("Content-Type", "application/json")
        .body(payload)
        .send()
        .await?
        .error_for_status()?;
    Ok(())
}

fn next_report_time(config: &RuntimeConfig) -> DateTime<Local> {
    let now = Local::now();
    let today = now.date_naive();
    let target = today
        .and_hms_opt(config.report_hour, config.report_minute, 0)
        .unwrap();
    let mut dt = Local.from_local_datetime(&target).unwrap();
    if dt <= now {
        let next = today.succ_opt().unwrap();
        let next_target = next
            .and_hms_opt(config.report_hour, config.report_minute, 0)
            .unwrap();
        dt = Local.from_local_datetime(&next_target).unwrap();
    }
    dt
}
