import { BrowserRouter, Routes, Route } from 'react-router-dom';
import DataSourcePage from './pages/DataSourcePage';
import TableSelectPage from './pages/TableSelectPage';
import DocPortalPage from './pages/DocPortalPage';
import SettingsPage from './pages/SettingsPage';
import CutoverSqlPage from './pages/CutoverSqlPage';
import './index.css';

function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <Routes>
          <Route path="/" element={<DataSourcePage />} />
          <Route path="/tables/:id" element={<TableSelectPage />} />
          <Route path="/docs/:id" element={<DocPortalPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/mapping" element={<CutoverSqlPage />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}

export default App;
