export const halfDays = [
  {value:'B1', label:'上午', start:'08:00', end:'13:00'},
  {value:'B2', label:'下午', start:'13:00', end:'18:00'},
  {value:'B3', label:'晚上', start:'18:00', end:'22:00'}
];

const DAY_MS = 86400000;
export const slotKey = ({date, slotId}) => date + '|' + slotId;

export function shanghaiDay(offset = 0, now = Date.now()) {
  return new Date(now + 8 * 3600000 + offset * DAY_MS).toISOString().slice(0, 10);
}

export const bookingDates = (now = Date.now()) =>
  Array.from({length:14}, (_, index) => shanghaiDay(index, now));

export function bookingPages(dates) {
  return [dates.slice(0, 7), dates.slice(7, 14)].filter(page => page.length);
}

export function longestBookingRun(cells) {
  const dates = [...new Set(cells.map(cell => cell.date))].sort();
  let previous = NaN, run = 0, longest = 0;
  for (const date of dates) {
    const day = Date.parse(date);
    run = day - previous === DAY_MS ? run + 1 : 1;
    longest = Math.max(longest, run);
    previous = day;
  }
  return longest;
}

export function canAddBookingCell(cells, date) {
  return longestBookingRun([...cells, {date}]) <= 7;
}

export function bookingCellReason(cell, participants = 1, now = Date.now()) {
  if (!cell?.enabled) return 'closed';
  const period = halfDays.find(item => item.value === cell.slotId);
  if (!period || !Number.isFinite(Date.parse(cell.date + 'T' + period.start + ':00+08:00'))) return 'closed';
  if (now >= Date.parse(cell.date + 'T' + period.start + ':00+08:00')) return 'started';
  const count = Math.max(1, Number(participants) || 1);
  return Number(cell.available) >= count ? '' : 'capacity';
}

function uniqueCells(cells) {
  return [...new Map(cells.map(cell => [slotKey(cell), {date:cell.date, slotId:cell.slotId}])).values()]
    .sort((left, right) => slotKey(left).localeCompare(slotKey(right)));
}

export function bookingGroupState(selected, candidates, rows, participants = 1, now = Date.now()) {
  const entries = new Map(rows.map(cell => [slotKey(cell), cell]));
  const picked = new Set(selected.map(slotKey));
  const eligible = uniqueCells(candidates).filter(cell =>
    !bookingCellReason(entries.get(slotKey(cell)), participants, now));
  if (!eligible.length) return candidates.some(cell => picked.has(slotKey(cell))) ? 'true' : 'false';
  const count = eligible.filter(cell => picked.has(slotKey(cell))).length;
  return count === eligible.length ? 'true' : count ? 'mixed' : 'false';
}

export function toggleBookingGroup(selected, candidates, rows, {participants = 1, now = Date.now()} = {}) {
  const targets = uniqueCells(candidates);
  const entries = new Map(rows.map(cell => [slotKey(cell), cell]));
  const targetKeys = new Set(targets.map(slotKey));
  if (bookingGroupState(selected, targets, rows, participants, now) === 'true') {
    return {cells:uniqueCells(selected.filter(cell => !targetKeys.has(slotKey(cell)))),
      action:'remove', skipped:0, reasons:{closed:0, started:0, capacity:0}, rejected:false};
  }
  const reasons = {closed:0, started:0, capacity:0};
  const eligible = targets.filter(cell => {
    const reason = bookingCellReason(entries.get(slotKey(cell)), participants, now);
    if (reason) reasons[reason]++;
    return !reason;
  });
  const merged = uniqueCells([...selected, ...eligible]);
  const skipped = Object.values(reasons).reduce((total, count) => total + count, 0);
  const rejected = longestBookingRun(merged) > 7;
  return {cells:rejected ? uniqueCells(selected) : merged,
    action:'add', skipped, reasons, rejected};
}
