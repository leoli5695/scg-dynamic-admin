import { useEffect, useRef } from 'react';

interface TechRaysBackgroundProps {
  /** Number of rays to draw */
  rayCount?: number;
  /** Number of particles to animate */
  particleCount?: number;
  /** Colors for rays and particles (rgba format without closing parenthesis) */
  colors?: string[];
  /** Canvas opacity (0-1) */
  opacity?: number;
  /** Responsive - automatically adjust ray/particle count on smaller screens */
  responsive?: boolean;
}

/**
 * TechRaysBackground - Animated canvas background with rays and meteor particles
 * Used in LoginPage for dynamic tech-themed visual effect
 */
const TechRaysBackground: React.FC<TechRaysBackgroundProps> = ({
  rayCount = 80,
  particleCount = 30,
  colors = ['rgba(96, 165, 250, ', 'rgba(167, 139, 250, ', 'rgba(34, 211, 238, '],
  opacity = 0.3,
  responsive = true,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Configuration - adjustable parameters
    const config = {
      rayCount: responsive && window.innerWidth < 768 ? Math.floor(rayCount / 2) : rayCount,
      particleCount: responsive && window.innerWidth < 768 ? Math.floor(particleCount / 2) : particleCount,
      colors,
      baseSpeed: 0.3,
      maxOpacity: 0.6,
      minOpacity: 0.2,
      particleSpeed: 0.5,
    };

    let animationFrameId: number;
    let rays: Array<{
      angle: number;
      speed: number;
      length: number;
      width: number;
      opacity: number;
      colorIndex: number;
      phase: number;
      pulseSpeed: number;
    }> = [];
    
    let particles: Array<{
      rayIndex: number;
      position: number;
      speed: number;
      size: number;
      opacity: number;
      colorIndex: number;
      trail: Array<{position: number; opacity: number}>;
    }> = [];

    const resizeCanvas = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resizeCanvas();

    const initRays = () => {
      rays = [];
      for (let i = 0; i < config.rayCount; i++) {
        rays.push({
          angle: (Math.PI * 2 * i) / config.rayCount,
          speed: config.baseSpeed + Math.random() * 0.2,
          length: 100 + Math.random() * 150,
          width: 1 + Math.random() * 2,
          opacity: config.minOpacity + Math.random() * (config.maxOpacity - config.minOpacity),
          colorIndex: Math.floor(Math.random() * config.colors.length),
          phase: Math.random() * Math.PI * 2,
          pulseSpeed: 0.01 + Math.random() * 0.02,
        });
      }
    };
    initRays();

    const initParticles = () => {
      particles = [];
      for (let i = 0; i < config.particleCount; i++) {
        particles.push({
          rayIndex: Math.floor(Math.random() * config.rayCount),
          position: Math.random(),
          speed: config.particleSpeed + Math.random() * 0.3,
          size: 2 + Math.random() * 3,
          opacity: 0.6 + Math.random() * 0.4,
          colorIndex: Math.floor(Math.random() * config.colors.length),
          trail: [],
        });
      }
    };
    initParticles();

    let lastTime = 0;
    const throttleInterval = 1000 / 30;

    const animate = (timestamp: number) => {
      if (timestamp - lastTime < throttleInterval) {
        animationFrameId = requestAnimationFrame(animate);
        return;
      }
      lastTime = timestamp;

      if (!ctx) return;
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      const centerX = canvas.width / 2;
      const centerY = canvas.height / 2;

      // Draw pulsing rays with glow
      rays.forEach((ray) => {
        ray.phase += ray.pulseSpeed;
        const pulseOpacity = ray.opacity * (0.6 + 0.4 * Math.sin(ray.phase));

        const endX = centerX + Math.cos(ray.angle) * ray.length * 3;
        const endY = centerY + Math.sin(ray.angle) * ray.length * 3;

        const gradient = ctx.createLinearGradient(centerX, centerY, endX, endY);
        gradient.addColorStop(0, config.colors[ray.colorIndex] + '0)');
        gradient.addColorStop(0.2, config.colors[ray.colorIndex] + pulseOpacity * 0.2 + ')');
        gradient.addColorStop(0.5, config.colors[ray.colorIndex] + pulseOpacity + ')');
        gradient.addColorStop(0.8, config.colors[ray.colorIndex] + pulseOpacity * 0.3 + ')');
        gradient.addColorStop(1, config.colors[ray.colorIndex] + '0)');

        ctx.beginPath();
        ctx.moveTo(centerX, centerY);
        ctx.lineTo(endX, endY);
        ctx.strokeStyle = gradient;
        ctx.lineWidth = ray.width;
        ctx.lineCap = 'round';
        
        ctx.shadowBlur = 15;
        ctx.shadowColor = config.colors[ray.colorIndex] + pulseOpacity + ')';
        ctx.stroke();
        ctx.shadowBlur = 0;

        ray.angle += 0.0001;
      });

      // Update and draw meteor particles with trails
      particles.forEach((particle) => {
        particle.position += particle.speed * 0.01;
        
        particle.trail.unshift({ position: particle.position, opacity: particle.opacity });
        if (particle.trail.length > 20) {
          particle.trail.pop();
        }
        
        if (particle.position > 1) {
          particle.position = 0;
          particle.rayIndex = Math.floor(Math.random() * config.rayCount);
          particle.trail = [];
        }

        const ray = rays[particle.rayIndex];
        if (!ray) return;

        const currentLength = ray.length * 3;
        const particleX = centerX + Math.cos(ray.angle) * currentLength * particle.position;
        const particleY = centerY + Math.sin(ray.angle) * currentLength * particle.position;

        // Draw trail
        particle.trail.forEach((trailPoint, index) => {
          const trailX = centerX + Math.cos(ray.angle) * currentLength * trailPoint.position;
          const trailY = centerY + Math.sin(ray.angle) * currentLength * trailPoint.position;
          const trailOpacity = trailPoint.opacity * (1 - index / particle.trail.length) * 0.6;
          
          ctx.beginPath();
          ctx.arc(trailX, trailY, particle.size * (1 - index / particle.trail.length), 0, Math.PI * 2);
          ctx.fillStyle = config.colors[particle.colorIndex] + trailOpacity + ')';
          ctx.fill();
        });

        // Draw particle head with sparkle
        ctx.beginPath();
        ctx.arc(particleX, particleY, particle.size, 0, Math.PI * 2);
        ctx.fillStyle = config.colors[particle.colorIndex] + particle.opacity + ')';
        
        ctx.shadowBlur = 20;
        ctx.shadowColor = config.colors[particle.colorIndex] + particle.opacity + ')';
        ctx.fill();
        ctx.shadowBlur = 0;

        if (Math.random() < 0.02) {
          particle.opacity = 0.8 + Math.random() * 0.2;
        } else {
          particle.opacity = 0.6 + Math.random() * 0.2;
        }
      });

      animationFrameId = requestAnimationFrame(animate);
    };

    animationFrameId = requestAnimationFrame(animate);

    const handleResize = () => {
      resizeCanvas();
      if (responsive) {
        config.rayCount = window.innerWidth < 768 ? Math.floor(rayCount / 2) : rayCount;
        config.particleCount = window.innerWidth < 768 ? Math.floor(particleCount / 2) : particleCount;
        initRays();
        initParticles();
      }
    };
    window.addEventListener('resize', handleResize);

    return () => {
      cancelAnimationFrame(animationFrameId);
      window.removeEventListener('resize', handleResize);
    };
  }, [rayCount, particleCount, colors, responsive]);

  return (
    <canvas
      ref={canvasRef}
      className="tech-rays-canvas"
      style={{ opacity }}
    />
  );
};

export default TechRaysBackground;