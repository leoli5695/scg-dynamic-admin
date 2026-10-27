import { useCallback, useState } from 'react';
import type { SetStateAction } from 'react';

export const useEditorStep = () => {
  const [step, setStep] = useState({ active: 0, direction: 'forward' });
  const setActive = useCallback((update: SetStateAction<number>) => {
    setStep((previous) => {
      const active = typeof update === 'function' ? update(previous.active) : update;
      return active === previous.active ? previous : {
        active, direction: active > previous.active ? 'forward' : 'backward',
      };
    });
  }, []);
  return [step.active, setActive, step.direction] as const;
};
