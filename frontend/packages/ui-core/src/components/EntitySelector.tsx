import React, { useState, useRef, useEffect } from 'react';
import { Building2, ChevronDown, Check, Globe } from 'lucide-react';
import { FiduciaryEntity, FiduciaryEntityId } from '@uds/api-client';
import { cn } from '../utils';

interface EntitySelectorProps {
  entities: FiduciaryEntity[];
  selectedEntityId?: FiduciaryEntityId | null;
  onSelectEntity: (entityId: FiduciaryEntityId | null) => void;
  className?: string;
  allowAllEntities?: boolean;
}

export const EntitySelector: React.FC<EntitySelectorProps> = ({
  entities,
  selectedEntityId,
  onSelectEntity,
  className,
  allowAllEntities = true
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const activeEntity = entities.find((e) => e.id === selectedEntityId);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  return (
    <div className={cn('relative', className)} ref={dropdownRef}>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2.5 px-3 py-1.5 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 border border-slate-300 dark:border-slate-700 rounded-[4px] text-xs font-medium text-slate-800 dark:text-slate-100 transition-colors focus:outline-none focus:ring-1 focus:ring-indigo-600"
      >
        <Building2 className="w-4 h-4 text-indigo-700 dark:text-indigo-400 shrink-0" />
        <div className="text-left">
          <div className="text-[10px] uppercase tracking-wider text-slate-500 dark:text-slate-400 leading-none">
            Fiduciary Scope
          </div>
          <div className="font-semibold text-slate-900 dark:text-slate-50 leading-tight mt-0.5 max-w-[170px] truncate">
            {activeEntity ? activeEntity.name : 'All UDS Group Entities'}
          </div>
        </div>
        <ChevronDown className={cn('w-3.5 h-3.5 text-slate-500 transition-transform ml-1', isOpen && 'rotate-180')} />
      </button>

      {isOpen && (
        <div className="absolute left-0 mt-1.5 w-72 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-[4px] shadow-lg z-50 py-1 divide-y divide-slate-100 dark:divide-slate-800 font-['Inter']">
          {allowAllEntities && (
            <div className="p-1">
              <button
                type="button"
                onClick={() => {
                  onSelectEntity(null);
                  setIsOpen(false);
                }}
                className={cn(
                  'w-full flex items-center justify-between px-2.5 py-2 text-xs rounded hover:bg-indigo-50 dark:hover:bg-indigo-950/40 text-left transition-colors',
                  selectedEntityId === null && 'bg-indigo-50/80 dark:bg-indigo-950/60 font-semibold text-indigo-900 dark:text-indigo-300'
                )}
              >
                <div className="flex items-center gap-2">
                  <Globe className="w-4 h-4 text-indigo-600 dark:text-indigo-400" />
                  <div>
                    <div className="font-medium text-slate-900 dark:text-slate-100">All UDS Group Entities</div>
                    <div className="text-[11px] text-slate-500">Group Rollup & Cross-Entity View</div>
                  </div>
                </div>
                {selectedEntityId === null && <Check className="w-4 h-4 text-indigo-600" />}
              </button>
            </div>
          )}

          <div className="p-1 max-h-64 overflow-y-auto">
            {entities.map((ent) => (
              <button
                key={ent.id}
                type="button"
                onClick={() => {
                  onSelectEntity(ent.id);
                  setIsOpen(false);
                }}
                className={cn(
                  'w-full flex items-center justify-between px-2.5 py-2 text-xs rounded hover:bg-indigo-50 dark:hover:bg-indigo-950/40 text-left transition-colors',
                  selectedEntityId === ent.id && 'bg-indigo-50/80 dark:bg-indigo-950/60 font-semibold text-indigo-900 dark:text-indigo-300'
                )}
              >
                <div>
                  <div className="font-medium text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    {ent.name}
                    <span className="text-[10px] px-1 py-0.2 bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400 rounded">
                      {ent.id}
                    </span>
                  </div>
                  <div className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                    {ent.residencyRegion}
                  </div>
                </div>
                {selectedEntityId === ent.id && <Check className="w-4 h-4 text-indigo-600" />}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
