import React, { useEffect, useState } from 'react'
import axios from 'axios'

const API = import.meta.env.VITE_BACKEND_URL || 'http://localhost:7001'

function RecipeCard({ recipe, onEdit, onDelete, onOpen }) {
  return (
    <article className="card" onClick={() => onOpen(recipe)}>
  <div className="card-image">{recipe.image && <img src={`${API}${recipe.image}`} alt={recipe.title} />}</div>
      <div className="card-body">
        <h3 className="card-title">{recipe.title}</h3>
        <p className="card-ingredients">{recipe.ingredients?.split('\n')[0]}</p>
      </div>
      <div className="card-actions">
        <button onClick={(e) => { e.stopPropagation(); onEdit(recipe) }}>Edit</button>
        <button onClick={(e) => { e.stopPropagation(); onDelete(recipe.id) }} className="danger">Delete</button>
      </div>
    </article>
  )
}

function RecipeModal({ recipe, onClose }) {
  if (!recipe) return null
  return (
    <div className="modal" onClick={onClose}>
      <div className="modal-content" onClick={e => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose}>×</button>
        <h2>{recipe.title}</h2>
        <h4>Ingredients</h4>
        <pre>{recipe.ingredients}</pre>
        <h4>Instructions</h4>
        <p>{recipe.instructions}</p>
      </div>
    </div>
  )
}

export default function App() {
  const [recipes, setRecipes] = useState([])
  const [form, setForm] = useState({ title: '', ingredients: '', instructions: '' })
  const [file, setFile] = useState(null)
  const [editingId, setEditingId] = useState(null)
  const [view, setView] = useState('list') // 'list' or 'add'
  const [toast, setToast] = useState({ message: '', visible: false })
  const [modalRecipe, setModalRecipe] = useState(null)

  useEffect(() => { fetchRecipes() }, [])

  async function fetchRecipes() {
    try {
      const res = await axios.get(`${API}/recipes`)
      setRecipes(res.data)
    } catch (e) {
      setToast({ message: 'Failed to load recipes', visible: true })
    }
  }

  async function submit(e) {
    e.preventDefault()
    try {
      const fd = new FormData()
      fd.append('title', form.title)
      fd.append('ingredients', form.ingredients)
      fd.append('instructions', form.instructions)
      if (file) fd.append('image', file)
      if (editingId) {
        await axios.put(`${API}/recipes/${editingId}`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        setToast({ message: 'Recipe updated', visible: true })
      } else {
        await axios.post(`${API}/recipes`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
        setToast({ message: 'Recipe saved', visible: true })
      }
      setForm({ title: '', ingredients: '', instructions: '' })
      setEditingId(null)
      setView('list')
      fetchRecipes()
    } catch (err) {
      setToast({ message: 'Save failed', visible: true })
    }
  }

  function startEdit(r) {
    setEditingId(r.id)
    setForm({ title: r.title || '', ingredients: r.ingredients || '', instructions: r.instructions || '' })
    setView('add')
  }

  async function remove(id) {
    if (!confirm('Delete this recipe?')) return
    try {
      await axios.delete(`${API}/recipes/${id}`)
      setToast({ message: 'Recipe deleted', visible: true })
      fetchRecipes()
    } catch (e) {
      setToast({ message: 'Delete failed', visible: true })
    }
  }

  useEffect(() => {
    let t
    if (toast.visible) t = setTimeout(() => setToast({ ...toast, visible: false }), 3000)
    return () => clearTimeout(t)
  }, [toast])

  return (
    <div className="container">
      <header className="nav">
        <h1 onClick={() => setView('list')} style={{ cursor: 'pointer' }}>Recipe Box</h1>
        <div>
          <button onClick={() => { setView('list'); setEditingId(null) }} className={view === 'list' ? 'active' : ''}>Home</button>
          <button onClick={() => { setView('add'); setEditingId(null) }} className={view === 'add' ? 'active' : ''} style={{ marginLeft: 8 }}>Add</button>
        </div>
      </header>

      {toast.visible && <div className="toast">{toast.message}</div>}

      {view === 'list' && (
        <main>
          <div className="grid">
            {recipes.map(r => (
              <RecipeCard key={r.id} recipe={r} onEdit={startEdit} onDelete={remove} onOpen={setModalRecipe} />
            ))}
          </div>
        </main>
      )}

      {view === 'add' && (
        <section className="form-wrap">
          <div className="form">
            <h2>{editingId ? 'Edit Recipe' : 'Add Recipe'}</h2>
            <form onSubmit={submit}>
              <label>Title</label>
              <input placeholder="Title" value={form.title} onChange={e => setForm({ ...form, title: e.target.value })} required />
              <label>Ingredients (one per line)</label>
              <textarea placeholder="Ingredients" value={form.ingredients} onChange={e => setForm({ ...form, ingredients: e.target.value })} />
              <label>Instructions</label>
              <textarea placeholder="Instructions" value={form.instructions} onChange={e => setForm({ ...form, instructions: e.target.value })} />
              <label>Image (optional)</label>
              <input type="file" accept="image/*" onChange={e => setFile(e.target.files[0])} />
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                <button type="submit">{editingId ? 'Update' : 'Save'}</button>
                <button type="button" onClick={() => { setEditingId(null); setForm({ title: '', ingredients: '', instructions: '' }); setView('list') }}>Cancel</button>
              </div>
            </form>
          </div>
        </section>
      )}

      <RecipeModal recipe={modalRecipe} onClose={() => setModalRecipe(null)} />
    </div>
  )
}
