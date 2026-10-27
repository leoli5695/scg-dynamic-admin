import { useEffect, useRef } from 'react';

/** Six small compositor layers, without a canvas or a JavaScript frame loop. */
export default function LoginParticles() {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    let resumeTimer: ReturnType<typeof setTimeout> | undefined;
    let scrolling = false;
    const onVisibilityChange = () => {
      container.toggleAttribute('data-hidden', document.hidden);
    };
    const onScroll = () => {
      // Update once when scrolling starts, rather than on every scroll event.
      if (!scrolling) {
        container.setAttribute('data-scrolling', '');
        scrolling = true;
      }
      clearTimeout(resumeTimer);
      resumeTimer = setTimeout(() => {
        scrolling = false;
        container.removeAttribute('data-scrolling');
      }, 200);
    };
    onVisibilityChange();
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      clearTimeout(resumeTimer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('scroll', onScroll);
    };
  }, []);

  return (
    <div className="login-particles" ref={containerRef} aria-hidden="true">
      {Array.from({ length: 6 }, (_, index) => (
        <span className="login-particle" key={index} />
      ))}
    </div>
  );
}
