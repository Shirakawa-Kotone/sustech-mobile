# SUSTech Academic Calendar Data

南方科技大学校历数据

## Files

| File | Description |
|------|-------------|
| `2026/general.json` | Holiday dates and compensatory workdays (same for all programs) |
| `2026/undergraduate.json` | Undergraduate-specific academic schedule |
| `2026/graduate.json` | Graduate-specific academic schedule |

## Data Source

SUSTech official academic calendar:
https://www.sustech.edu.cn/zh/academic-calendar.html

Source PDF: `2026/academic-calendar-2026.pdf`

## Format

### general.json

```json
{
    "holidays": [
        {"name": "春节", "start": "2026-02-15", "end": "2026-02-23"}
    ],
    "compensatory_workdays": ["2026-02-28"]
}
```

### undergraduate.json / graduate.json

```json
{
    "winter_holiday": {"start": "2026-01-12", "end": "2026-02-23"},
    "spring_semester": {
        "start": "2026-02-23",
        "end": "2026-06-30",
        "sign_in": "2026-02-24",
        "teaching_start": "2026-02-25",
        "total_teaching_weeks": 15,
        "midterm": {"start": "2026-04-13", "end": "2026-04-26", "equivalent_weeks": [8, 9]},
        "final": {"start": "2026-06-08", "end": "2026-06-18", "equivalent_weeks": [16, 17]},
        "compensatories": [{"date": "2026-02-28", "week_type": "odd", "workday_type": "Monday"}],
        "extra_breaks": ["2026-04-04"]
    },
    "summer_semester": {"start": "2026-06-29", "end": "2026-08-07"},
    "summer_holiday": {"start": "2026-06-29", "end": "2026-09-03"},
    "fall_semester": {
        "start": "2026-09-01",
        "end": "2027-01-11",
        "sign_in": "2026-09-04",
        "teaching_start": "2026-09-07",
        "freshman_arrival": "2026-08-17",
        "total_teaching_weeks": 16,
        "midterm": {"start": "2026-10-26", "end": "2026-11-08", "equivalent_weeks": [8, 9]},
        "final": {"start": "2026-12-28", "end": "2027-01-08", "equivalent_weeks": [17]},
        "compensatories": [
            {"date": "2026-09-20", "week_type": "odd", "workday_type": "Friday"},
            {"date": "2026-10-10", "week_type": "odd", "workday_type": "Wednesday"}
        ],
        "extra_breaks": ["2026-11-20"]
    }
}
```

## Fields

### general.json

- `holidays[]`: gov-published holiday ranges
  - `name`: holiday name
  - `start`: first day of holiday
  - `end`: last day of holiday
- `compensatory_workdays[]`: dates that are workdays despite surrounding holiday (ISO date strings)

### undergraduate.json / graduate.json

- `winter_holiday`: winter break before spring semester
- `spring_semester`: spring teaching period
  - `total_teaching_weeks`: number of teaching weeks
  - `midterm.equivalent_weeks[]`: which calendar weeks map to midterm period (for odd/even week courses)
  - `final.equivalent_weeks[]`: which calendar weeks map to final exam period
  - `compensatories[]`: compensatory teaching days on holidays
    - `week_type`: "odd" or "even"
    - `workday_type`: day of week being made up (e.g. "Monday")
  - `extra_breaks[]`: non-holiday breaks (e.g. sports day)
- `summer_semester`: optional summer teaching period
- `summer_holiday`: summer break
- `fall_semester`: fall teaching period

## Usage

```bash
curl https://raw.githubusercontent.com/dumixthestpd/sustech-calendar/main/2026/general.json
curl https://raw.githubusercontent.com/dumixthestpd/sustech-calendar/main/2026/undergraduate.json
```
