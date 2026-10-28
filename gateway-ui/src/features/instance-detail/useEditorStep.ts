import { useCallback, useState } from 'react';
import type { SetStateAction } from 'react';

export const useEditorStep = () => {
  const [step, setStep] = useState({ active: 0, direction: 'forward', visited: [0] });
  const setActive = useCallback((update: SetStateAction<number>) => {
    setStep((previous) => {
      const active = typeof update === 'function' ? update(previous.active) : update;
      return active === previous.active ? previous : {
        active, direction: active > previous.active ? 'forward' : 'backward',
        visited: previous.visited.includes(active) ? previous.visited : [...previous.visited, active],
      };
    });
  }, []);
  return [step.active, setActive, step.direction, step.visited] as const;
};
