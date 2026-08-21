import React from 'react';
import { cn } from '../utils';

interface StatCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  change?: string;
  changeType?: 'positive' | 'negative' | 'neutral' | 'warning';
  icon?: React.ReactNode;
  activeBorder?: boolean;
  className?: string;
}

export const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  subtitle,
  change,
  changeType = 'neutral',
  icon,
  activeBorder = false,
  className
}) => {
  const changeStyles = {
    positive: 'text-emerald-700 bg-emerald-50 border-emerald-200 dark:text-emerald-400 dark:bg-emerald-950/50',
    negative: 'text-rose-700 bg-rose-50 border-rose-200 dark:text-rose-400 dark:bg-rose-950/50',
    warning: 'text-amber-700 bg-amber-50 border-amber-200 dark:text-amber-400 dark:bg-amber-950/50',
    neutral: 'text-slate-600 bg-slate-50 border-slate-200 dark:text-slate-400 dark:bg-slate-800'
  };

  return (
    <div
      className={cn(
        'bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-[4px] p-5 shadow-[0_1px_2px_rgba(0,0,0,0.04)] relative transition-all',
        activeBorder && 'border-t-2 border-t-[#1f108e] dark:border-t-indigo-500',
        className
      )}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider font-['Inter']">
            {title}
          </p>
          <h3 className="text-2xl font-bold text-slate-900 dark:text-slate-50 mt-1.5 font-['Inter'] tracking-tight">
            {value}
          </h3>
        </div>
        {icon && (
          <div className="p-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-[4px] text-slate-700 dark:text-slate-300">
            {icon}
          </div>
        )}
      </div>

      {(subtitle || change) && (
        <div className="mt-3.5 flex items-center gap-2 text-xs">
          {change && (
            <span className={cn('px-1.5 py-0.5 rounded-[2px] font-medium border text-[11px]', changeStyles[changeType])}>
              {change}
            </span>
          )}
          {subtitle && (
            <span className="text-slate-500 dark:text-slate-400 font-['Inter']">
              {subtitle}
            </span>
          )}
        </div>
      )}
    </div>
  );
};
