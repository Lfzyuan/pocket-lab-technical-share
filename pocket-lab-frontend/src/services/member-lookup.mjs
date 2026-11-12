export function createDirectoryLookup({search, onState, delay = 250, schedule = setTimeout, cancel = clearTimeout}) {
  let revision = 0, timer, disposed = false;

  function clear() {
    revision++;
    if (timer !== undefined) cancel(timer);
    timer = undefined;
    if (!disposed) onState({phase:'idle', items:[], query:'', data:null});
  }

  function lookup(value) {
    clear();
    if (disposed) return;
    const query = String(value || '').trim();
    if (!query) return;
    const current = revision;
    onState({phase:'loading', items:[], query, data:null});
    timer = schedule(async () => {
      timer = undefined;
      try {
        const result = await search(query);
        const items = Array.isArray(result) ? result : result?.items;
        if (!Array.isArray(items)) throw Error('Invalid directory response');
        if (!disposed && current === revision) {
          onState({phase:'ready', items, query, data:Array.isArray(result) ? null : result});
        }
      } catch {
        if (!disposed && current === revision) onState({phase:'error', items:[], query, data:null});
      }
    }, delay);
  }

  function dispose() {
    clear();
    disposed = true;
  }

  return {lookup, clear, dispose};
}

export const createMemberLookup = createDirectoryLookup;
