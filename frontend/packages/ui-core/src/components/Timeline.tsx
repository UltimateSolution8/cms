import React from 'react';
import { ShieldCheck, ArrowRight, Eye, CheckCircle2, XCircle, FileText, UserCheck } from 'lucide-react';
import { ConsentEventTimelineItem } from '@uds/api-client';
import { HashDisplay } from './HashDisplay';
import { Badge } from './Badge';
import { formatDate } from '../utils';

interface TimelineProps {
  events: ConsentEventTimelineItem[];
  className?: string;
}

export const Timeline: React.FC<TimelineProps> = ({ events, className }) => {
  const getEventIcon = (type: string) => {
    switch (type) {
      case 'CONSENT_GRANTED':
        return <CheckCircle2 className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />;
      case 'CONSENT_WITHDRAWN':
        return <XCircle className="w-4 h-4 text-rose-600 dark:text-rose-400" />;
      case 'NOTICE_SERVED':
        return <Eye className="w-4 h-4 text-indigo-600 dark:text-indigo-400" />;
      case 'SUBJECT_MERGED':
        return <UserCheck className="w-4 h-4 text-amber-600 dark:text-amber-400" />;
      default:
        return <FileText className="w-4 h-4 text-slate-600 dark:text-slate-400" />;
    }
  };

  const getEventBadge = (type: string) => {
    switch (type) {
      case 'CONSENT_GRANTED':
        return <Badge variant="success">CONSENT GRANTED</Badge>;
      case 'CONSENT_WITHDRAWN':
        return <Badge variant="critical">WITHDRAWN</Badge>;
      case 'NOTICE_SERVED':
        return <Badge variant="primary">NOTICE SERVED</Badge>;
      case 'SUBJECT_MERGED':
        return <Badge variant="warning">IDENTITY MERGED</Badge>;
      default:
        return <Badge variant="neutral">{type}</Badge>;
    }
  };

  return (
    <div className={`relative pl-6 space-y-6 ${className || ''}`}>
      {/* Vertical Connecting Line */}
      <div className="absolute left-[11px] top-2 bottom-2 w-0.5 bg-slate-200 dark:bg-slate-700" />

      {events.map((evt, idx) => (
        <div key={evt.eventId || idx} className="relative group">
          {/* Circular Node */}
          <div className="absolute -left-6 top-1.5 w-6 h-6 rounded-full bg-white dark:bg-slate-900 border-2 border-indigo-600 dark:border-indigo-400 flex items-center justify-center shadow-sm z-10">
            {getEventIcon(evt.eventType)}
          </div>

          {/* Event Content Card */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-[4px] p-4 shadow-sm ml-2">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 dark:border-slate-800 pb-2.5 mb-3">
              <div className="flex items-center gap-2">
                <span className="font-['JetBrains_Mono'] text-xs font-semibold text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-800 px-1.5 py-0.5 rounded">
                  #{evt.sequenceNumber}
                </span>
                {getEventBadge(evt.eventType)}
                {evt.verifiedChain && (
                  <span className="inline-flex items-center gap-1 text-[11px] font-medium text-emerald-700 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-950/40 px-1.5 py-0.5 rounded border border-emerald-200 dark:border-emerald-800">
                    <ShieldCheck className="w-3 h-3" />
                    Hash-Chain Verified
                  </span>
                )}
              </div>
              <span className="font-['JetBrains_Mono'] text-xs text-slate-500 dark:text-slate-400">
                {formatDate(evt.timestamp)}
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-slate-600 dark:text-slate-300">
              <div>
                <span className="text-slate-400 dark:text-slate-500 font-medium">Channel / Method: </span>
                <span className="font-semibold text-slate-800 dark:text-slate-200">
                  {evt.channel} {evt.captureMethod ? `(${evt.captureMethod})` : ''}
                </span>
              </div>
              {evt.purposeCode && (
                <div>
                  <span className="text-slate-400 dark:text-slate-500 font-medium">Purpose: </span>
                  <span className="font-semibold text-indigo-700 dark:text-indigo-400">{evt.purposeCode}</span>
                </div>
              )}
              {evt.actorId && (
                <div>
                  <span className="text-slate-400 dark:text-slate-500 font-medium">Actor / Human: </span>
                  <span className="font-semibold text-slate-800 dark:text-slate-200">{evt.actorId}</span>
                </div>
              )}
              {evt.noticeVersion && (
                <div>
                  <span className="text-slate-400 dark:text-slate-500 font-medium">Notice Version: </span>
                  <span className="font-semibold text-slate-800 dark:text-slate-200">v{evt.noticeVersion}</span>
                </div>
              )}
            </div>

            {/* Cryptographic SHA-256 Provenance Box */}
            <div className="mt-3.5 pt-3 border-t border-dashed border-slate-200 dark:border-slate-800 bg-slate-50/60 dark:bg-slate-800/40 p-2.5 rounded-[3px]">
              <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">
                Cryptographic Linkage (SHA-256)
              </div>
              <div className="flex flex-col sm:flex-row sm:items-center gap-1.5 sm:gap-3 text-xs">
                <div className="flex items-center gap-1">
                  <span className="text-slate-400 text-[11px]">Prev:</span>
                  <HashDisplay hash={evt.previousHash} lead={6} trail={6} />
                </div>
                <ArrowRight className="w-3 h-3 text-slate-400 hidden sm:block" />
                <div className="flex items-center gap-1">
                  <span className="text-slate-400 text-[11px]">Node:</span>
                  <HashDisplay hash={evt.sha256Hash} lead={6} trail={6} />
                </div>
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};
