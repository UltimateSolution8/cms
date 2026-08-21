import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDate(isoString?: string | null): string {
  if (!isoString) return 'Not recorded';
  try {
    const d = new Date(isoString);
    return new Intl.DateTimeFormat('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).format(d);
  } catch {
    return isoString;
  }
}

export function truncateHash(hash?: string, lead = 8, trail = 8): string {
  if (!hash) return '';
  if (hash.length <= lead + trail + 3) return hash;
  return `${hash.slice(0, lead)}...${hash.slice(-trail)}`;
}
