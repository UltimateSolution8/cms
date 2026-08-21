import React, { createContext, useContext, useState, useEffect } from 'react';
import { FiduciaryEntityId, UdsApiClient } from '@uds/api-client';

export interface UserPersona {
  username: string;
  name: string;
  email: string;
  role: 'ADMIN' | 'CAPTURE' | 'DECISION';
  entityId: FiduciaryEntityId | null;
  assignedRoles: string[];
  isOverAssigned?: boolean;
}

export const DEV_PERSONAS: UserPersona[] = [
  {
    username: 'uds.dpo',
    name: 'Chief Data Protection Officer',
    email: 'dpo.officer@uds.co.in',
    role: 'ADMIN',
    entityId: null, // Group-level access
    assignedRoles: ['consent.admin', 'uds.dpo']
  },
  {
    username: 'denave.operator',
    name: 'Denave Compliance Officer',
    email: 'compliance.denave@uds.co.in',
    role: 'ADMIN',
    entityId: 'DENAVE_IN',
    assignedRoles: ['consent.admin', 'entity.DENAVE_IN']
  },
  {
    username: 'matrix.operator',
    name: 'Matrix BGV Compliance Lead',
    email: 'compliance.matrix@uds.co.in',
    role: 'ADMIN',
    entityId: 'MATRIX',
    assignedRoles: ['consent.admin', 'entity.MATRIX']
  },
  {
    username: 'over.assigned',
    name: 'Misconfigured Operator',
    email: 'overassigned@uds.co.in',
    role: 'ADMIN',
    entityId: null,
    assignedRoles: ['consent.admin', 'entity.DENAVE_IN', 'entity.MATRIX'],
    isOverAssigned: true // triggers 403 error page test
  }
];

interface AuthContextType {
  user: UserPersona | null;
  isAuthenticated: boolean;
  selectedEntityId: FiduciaryEntityId | null;
  setSelectedEntityId: (id: FiduciaryEntityId | null) => void;
  loginWithPersona: (persona: UserPersona) => void;
  logout: () => void;
  apiClient: UdsApiClient;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserPersona | null>(() => {
    const saved = localStorage.getItem('uds_auth_user');
    return saved ? JSON.parse(saved) : DEV_PERSONAS[0];
  });

  const [selectedEntityId, setSelectedEntityIdState] = useState<FiduciaryEntityId | null>(() => {
    const saved = localStorage.getItem('uds_selected_entity');
    return saved ? (saved as FiduciaryEntityId) : user?.entityId || null;
  });

  const setSelectedEntityId = (id: FiduciaryEntityId | null) => {
    setSelectedEntityIdState(id);
    if (id) {
      localStorage.setItem('uds_selected_entity', id);
    } else {
      localStorage.removeItem('uds_selected_entity');
    }
  };

  const loginWithPersona = (persona: UserPersona) => {
    setUser(persona);
    localStorage.setItem('uds_auth_user', JSON.stringify(persona));
    setSelectedEntityId(persona.entityId);
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem('uds_auth_user');
    localStorage.removeItem('uds_selected_entity');
  };

  const apiClient = new UdsApiClient({
    baseUrl: '', // Proxy /v1 to Spring Boot port 8080
    getToken: () => (user ? `mock-jwt-token-for-${user.username}` : null),
    getEntityScope: () => selectedEntityId,
    getActor: () => user?.email || 'console-operator'
  });

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        selectedEntityId,
        setSelectedEntityId,
        loginWithPersona,
        logout,
        apiClient
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
