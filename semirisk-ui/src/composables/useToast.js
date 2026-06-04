export function useToast(state) {
  function notify(message) {
    state.toast = message;
    setTimeout(() => (state.toast = ''), 2600);
  }

  return { notify };
}
