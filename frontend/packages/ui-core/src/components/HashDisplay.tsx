import React, { useState } from 'react';
import { Copy, Check } from 'lucide-react';
import { cn, truncateHash } from '../utils';

interface HashDisplayProps {
  hash: string;
  lead?: number;
  trail?: number;
  copyable?: boolean;
  className?: string;
  showFullOnHover?: boolean;
}

export const HashDisplay: React.FC<HashDisplayProps> = ({
  hash,
  lead = 8,
  trail = 8,
  copyable = true,
  className,
  showFullOnHover = true
}) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(hash);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const displayText = truncateHash(hash, lead, trail);

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 font-["JetBrains_Mono"] text-[12px] bg-slate-50 dark:bg-slate-800/80 px-2 py-0.5 rounded-[2px] border border-slate-200 dark:border-slate-700 text-slate-800 dark:text-slate-200 group relative',
        className
      )}
      title={showFullOnHover ? hash : undefined}
    >
      <span className="select-all tracking-tight">{displayText}</span>
      {copyable && (
        <button
          type="button"
          onClick={handleCopy}
          className="text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 focus:outline-none transition-colors ml-0.5"
          title="Copy full cryptographic hash"
          aria-label="Copy hash"
        >
          {copied ? (
            <Check className="w-3 h-3 text-emerald-600 dark:text-emerald-400" />
          ) : (
            <Copy className="w-3 h-3 opacity-60 group-hover:opacity-100" />
          )}
        </button>
      )}
    </span>
  );
};
