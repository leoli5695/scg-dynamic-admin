import React from 'react';

interface GatewayLogoProps {
  className?: string;
  style?: React.CSSProperties;
}

const GatewayLogo: React.FC<GatewayLogoProps> = ({ className, style }) => (
  <svg
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    className={className}
    style={style}
  >
    <defs>
      <linearGradient id="logoGrad" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stopColor="#818CF8" />
        <stop offset="100%" stopColor="#60A5FA" />
      </linearGradient>
    </defs>
    {/* Hexagon background */}
    <polygon
      points="16,2 28,9 28,23 16,30 4,23 4,9"
      fill="url(#logoGrad)"
    />
    {/* Center hub */}
    <circle cx="16" cy="16" r="4" fill="white" />
    {/* Connection nodes - 6 directions */}
    <circle cx="16" cy="7" r="2.5" fill="rgba(255,255,255,0.9)" />
    <circle cx="23.5" cy="12" r="2.5" fill="rgba(255,255,255,0.9)" />
    <circle cx="23.5" cy="20" r="2.5" fill="rgba(255,255,255,0.9)" />
    <circle cx="16" cy="25" r="2.5" fill="rgba(255,255,255,0.9)" />
    <circle cx="8.5" cy="20" r="2.5" fill="rgba(255,255,255,0.9)" />
    <circle cx="8.5" cy="12" r="2.5" fill="rgba(255,255,255,0.9)" />
    {/* Connection lines */}
    <g stroke="rgba(255,255,255,0.6)" strokeWidth="1.5">
      <line x1="16" y1="12" x2="16" y2="9.5" />
      <line x1="19.5" y1="14" x2="21" y2="12.5" />
      <line x1="19.5" y1="18" x2="21" y2="19.5" />
      <line x1="16" y1="20" x2="16" y2="22.5" />
      <line x1="12.5" y1="18" x2="11" y2="19.5" />
      <line x1="12.5" y1="14" x2="11" y2="12.5" />
    </g>
  </svg>
);

export default GatewayLogo;